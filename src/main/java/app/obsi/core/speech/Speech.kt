package app.obsi.core.speech

import app.obsi.core.data.HttpWords

import android.content.Context
import android.net.Uri
import app.obsi.core.data.Cue
import app.obsi.core.data.TranscriptData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/** One spoken word and when it was said, in seconds. */
data class Word(val text: String, val start: Double, val end: Double)

/** Turning speech into a transcript, for any Obsi app. The engines are in [Cloud]; the phone engine lives in the app that ships it. */
object Speech {
    /**
     * Groups words into caption cues: a new cue after a pause, after a sentence end once the cue is long
     * enough, or at 14 words. Each cue keeps every word's own start, so a highlight lands on the right word.
     */
    fun cues(words: List<Word>): List<Cue> {
        val out = ArrayList<Cue>()
        var group = ArrayList<Word>()
        fun flush() {
            if (group.isEmpty()) return
            val start = group.first().start
            val end = maxOf(group.last().end, start + 0.2)
            out += Cue(start, end - start, group.joinToString(" ") { it.text }, group.map { (it.start - start).coerceAtLeast(0.0) })
            group = ArrayList()
        }
        for (w in words.sortedBy { it.start }) {
            val prev = group.lastOrNull()
            if (prev != null && (w.start - prev.end > 0.8 || group.size >= 14 || (group.size >= 6 && prev.text.last() in ".?!"))) flush()
            group += w
        }
        flush()
        return out
    }
}

/**
 * Any service with the OpenAI transcription API: OpenAI, Groq, OpenRouter, or your own server. You bring
 * the key. The sound is cut into pieces of about 5 minutes, each at the quietest moment near its end so no
 * word is cut in two, and each is sent as soon as it is ready, so a long file never sits in memory whole.
 */
class Cloud(
    private val http: OkHttpClient,
    val url: String,
    private val key: String,
    val model: String,
    /** Blank lets the service find the language. */
    val language: String = "",
    /** Words the speech is likely to have, like the lesson title, so names are spelled right. */
    val hint: String = "",
) {
    suspend fun transcribe(context: Context, uri: Uri, headers: Map<String, String>, onProgress: (Float) -> Unit): TranscriptData {
        val words = ArrayList<Word>()
        val buf = ByteArrayOutputStream()
        // Where the sound in buf starts, in seconds.
        var pieceStart = 0.0
        var sent = 0
        var heard = false
        fun send(bytes: ByteArray) {
            val offset = pieceStart
            // The end of the piece before helps the service carry on in the same style and spelling.
            val before = words.takeLast(40).joinToString(" ") { it.text }
            val got = send(bytes, listOf(hint, before).filter { it.isNotBlank() }.joinToString(" ").takeLast(800))
            words += got.map { it.copy(start = it.start + offset, end = it.end + offset) }
            sent++
        }
        fun flush(all: Boolean) {
            if (buf.size() == 0) return
            val bytes = buf.toByteArray()
            val cut = if (all) bytes.size else Pcm.quietCut(bytes, Pcm.RATE * 20)
            send(bytes.copyOfRange(0, cut))
            buf.reset()
            buf.write(bytes, cut, bytes.size - cut)
            pieceStart += cut / 2.0 / Pcm.RATE
        }
        Pcm.decode(context, uri, headers, onProgress) { block ->
            val bb = java.nio.ByteBuffer.allocate(block.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            block.forEach { bb.putShort(it) }
            buf.write(bb.array())
            if (block.isNotEmpty()) heard = true
            if (buf.size() >= PIECE * 2) flush(all = false)
        }
        flush(all = true)
        if (!heard || sent == 0) error("This file has no sound to transcribe.")
        if (words.isEmpty()) error("No speech was heard.")
        return TranscriptData(lang = language, auto = true, cues = Speech.cues(words))
    }

    private fun send(pcm: ByteArray, prompt: String): List<Word> {
        val wav = Pcm.wavHeader(pcm.size) + pcm
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("temperature", "0")
            .apply { if (language.isNotBlank()) addFormDataPart("language", language) }
            .apply { if (prompt.isNotBlank()) addFormDataPart("prompt", prompt) }
            .build()
        val req = Request.Builder().url(url.trimEnd('/') + "/audio/transcriptions")
            .header("Authorization", "Bearer " + key.trim())
            .post(form).build()
        val text = http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401) error("The service did not take your key.")
            if (!r.isSuccessful) error(HttpWords.say(r.code, "The speech service"))
            body
        }
        return parse(text)
    }

    companion object {
        /** About 5 minutes of 16 kHz sound: under every service's size limit, and quick enough for its time limit. */
        const val PIECE = Pcm.RATE * 60 * 5

        private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /**
         * Words from an OpenAI-style answer. The segments have the text with its capitals and stops; the
         * word list has the times but no stops. So the segment text is used, and each of its words gets the
         * time of the same word in the list. Without a word list, each segment's words are spread over it.
         */
        fun parse(text: String): List<Word> {
            val o = Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(text).jsonObject
            fun JsonObject.d(k: String) = this[k]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val timed = o["words"]?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull { w ->
                val t = w["word"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                if (t.isEmpty()) null else Word(t, w.d("start"), w.d("end"))
            }
            val segs = o["segments"]?.jsonArray.orEmpty().map { it.jsonObject }
            if (segs.isEmpty()) return timed
            val out = ArrayList<Word>()
            var j = 0
            for (seg in segs) {
                val parts = seg["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
                val a = seg.d("start"); val b = seg.d("end")
                parts.forEachIndexed { i, p ->
                    val n = norm(p)
                    // A mark on its own, like a dash, joins the word before it.
                    if (n.isEmpty() && out.isNotEmpty()) { out[out.lastIndex] = out.last().let { it.copy(text = it.text + " " + p) }; return@forEachIndexed }
                    val k = (j until minOf(j + 6, timed.size)).firstOrNull { norm(timed[it].text) == n }
                    out += when {
                        k != null -> { j = k + 1; timed[k].copy(text = p) }
                        timed.isEmpty() -> Word(p, a + (b - a) * i / parts.size, a + (b - a) * (i + 1) / parts.size)
                        // Not in the list: it goes right after the word before.
                        else -> { val t = out.lastOrNull()?.end ?: a; Word(p, t, t) }
                    }
                }
            }
            return out
        }
    }
}
