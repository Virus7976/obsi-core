package app.obsi.core.speech

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
 * Any service with the OpenAI transcription API: OpenAI, Groq, or your own server. You bring the key.
 * The sound is cut into 10-minute pieces (about 19 MB each, under the 25 MB limit) and each is sent
 * as soon as it is ready, so a long file never sits in memory whole.
 */
class Cloud(
    private val http: OkHttpClient,
    val url: String,
    private val key: String,
    val model: String,
    /** Blank lets the service find the language. */
    val language: String = "",
) {
    suspend fun transcribe(context: Context, uri: Uri, headers: Map<String, String>, onProgress: (Float) -> Unit): TranscriptData {
        val words = ArrayList<Word>()
        val buf = ByteArrayOutputStream()
        var samples = 0L
        var pieceStart = 0.0
        var sent = 0
        val perPiece = Pcm.RATE * 60 * 10
        fun flush() {
            if (buf.size() == 0) return
            val offset = pieceStart
            words += send(buf.toByteArray()).map { it.copy(start = it.start + offset, end = it.end + offset) }
            buf.reset()
            sent++
            pieceStart = samples.toDouble() / Pcm.RATE
        }
        Pcm.decode(context, uri, headers, onProgress) { block ->
            val bb = java.nio.ByteBuffer.allocate(block.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            block.forEach { bb.putShort(it) }
            buf.write(bb.array())
            samples += block.size
            if (buf.size() >= perPiece * 2) flush()
        }
        flush()
        if (sent == 0) error("This file has no sound to transcribe.")
        if (words.isEmpty()) error("No speech was heard.")
        return TranscriptData(lang = language, auto = true, cues = Speech.cues(words))
    }

    private fun send(pcm: ByteArray): List<Word> {
        val wav = Pcm.wavHeader(pcm.size) + pcm
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .apply { if (language.isNotBlank()) addFormDataPart("language", language) }
            .build()
        val req = Request.Builder().url(url.trimEnd('/') + "/audio/transcriptions")
            .header("Authorization", "Bearer " + key.trim())
            .post(form).build()
        val text = http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401) error("The service did not take your key.")
            if (!r.isSuccessful) error("The service said ${r.code}: " + body.take(160))
            body
        }
        return parse(text)
    }

    companion object {
        /** Words from an OpenAI-style answer. Without word times, each segment's words are spread over it. */
        fun parse(text: String): List<Word> {
            val o = Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(text).jsonObject
            fun JsonObject.d(k: String) = this[k]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val words = o["words"]?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull { w ->
                val t = w["word"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                if (t.isEmpty()) null else Word(t, w.d("start"), w.d("end"))
            }
            if (words.isNotEmpty()) return words
            return o["segments"]?.jsonArray.orEmpty().map { it.jsonObject }.flatMap { seg ->
                val parts = seg["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val a = seg.d("start"); val b = seg.d("end")
                parts.mapIndexed { i, t -> Word(t, a + (b - a) * i / parts.size, a + (b - a) * (i + 1) / parts.size) }
            }
        }
    }
}
