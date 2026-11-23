package app.obsi.core.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Microsoft's neural voices (the same ones Edge reads with) through Microsoft's official Azure Speech
 * service. The person brings their own key and region; Azure's free tier covers about half a million
 * letters a month. The service gives no word times here, so the player spreads words by length.
 */
object MicrosoftVoices {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Every voice the key can use. Network call: run it off the main thread. */
    fun voices(key: String, region: String): List<SpeakerVoice> {
        val req = Request.Builder().url("https://${region.trim()}.tts.speech.microsoft.com/cognitiveservices/voices/list")
            .header("Ocp-Apim-Subscription-Key", key.trim()).build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException("The Microsoft key or region is wrong.")
            if (!r.isSuccessful) throw IOException("Microsoft answered ${r.code}.")
            return parse(r.body!!.string())
        }
    }

    fun parse(body: String): List<SpeakerVoice> {
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val short = s("ShortName").ifBlank { return@mapNotNull null }
            SpeakerVoice(
                id = "ms:$short",
                name = s("DisplayName").ifBlank { short },
                locale = s("Locale"),
                gender = s("Gender"),
                kind = VoiceKind.MICROSOFT,
                note = if (short.contains("Multilingual")) "Multilingual" else "",
            )
        }
    }

    /** Speaks [text] with the voice [shortName] into [out] as MP3. Blocks: call it off the main thread. */
    fun synth(key: String, region: String, shortName: String, text: String, out: File) {
        val lang = shortName.split('-').take(2).joinToString("-").ifBlank { "en-US" }
        val safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val ssml = "<speak version='1.0' xml:lang='$lang'><voice name='$shortName'>$safe</voice></speak>"
        val req = Request.Builder().url("https://${region.trim()}.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", key.trim())
            .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
            .header("User-Agent", "Obsi")
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException("The Microsoft key or region is wrong.")
            if (r.code == 429) throw IOException("Microsoft says too many requests. Try again soon.")
            if (!r.isSuccessful) throw IOException("Microsoft answered ${r.code}.")
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".part")
            tmp.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
        }
    }
}
