package app.obsi.core.speech

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone's own text-to-speech, usually Google's. Each piece of text is spoken into a WAV file, so the
 * player can pause, seek and change speed like any recording. The phone says when each word starts,
 * which drives the word highlight. Google's online voices work the same way, so a piece made once is kept
 * and plays later with no internet.
 */
class PhoneVoices(context: Context) {
    private val app = context.applicationContext
    private val ready = CountDownLatch(1)
    @Volatile private var ok = false
    private val lock = Any()

    private class Job {
        val done = CountDownLatch(1)
        @Volatile var rate = 0
        @Volatile var failed = false
        val words: MutableList<Triple<Int, Int, Int>> = Collections.synchronizedList(ArrayList())
    }

    private val jobs = ConcurrentHashMap<String, Job>()

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        ok = status == TextToSpeech.SUCCESS
        ready.countDown()
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { jobs[id]?.done?.countDown() }
            @Deprecated("old API") override fun onError(id: String) { jobs[id]?.apply { failed = true; done.countDown() } }
            override fun onError(id: String, errorCode: Int) { jobs[id]?.apply { failed = true; done.countDown() } }
            override fun onBeginSynthesis(id: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) { jobs[id]?.rate = sampleRateInHz }
            override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) { jobs[id]?.words?.add(Triple(start, end, frame)) }
        })
    }

    private fun awaitReady(): Boolean {
        ready.await(8, TimeUnit.SECONDS)
        return ok
    }

    /** The name of the phone's voice app, like "Speech Services by Google". */
    fun engineName(): String = runCatching {
        val pkg = tts.defaultEngine ?: return ""
        app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")

    /**
     * Every voice the phone can use now, best first: offline before online, higher quality first.
     * Voices that still need a download are left out (they would fail).
     */
    fun voices(): List<SpeakerVoice> {
        if (!awaitReady()) return emptyList()
        val all = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
        return all.filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { -it.quality }, { it.locale.toLanguageTag() }, { it.name }))
            .map { v ->
                SpeakerVoice(
                    id = "phone:${v.name}",
                    name = friendly(v.name, v.locale),
                    locale = v.locale.toLanguageTag(),
                    // Google does not say which voice is which gender, so none is shown rather than a guess.
                    kind = if (v.isNetworkConnectionRequired) VoiceKind.PHONE_ONLINE else VoiceKind.PHONE,
                    note = if (v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH) "Very high quality" else if (v.quality >= android.speech.tts.Voice.QUALITY_HIGH) "High quality" else "",
                )
            }
    }

    /** Google names look like en-us-x-iol-local. The short code tells two voices apart. */
    private fun friendly(name: String, locale: Locale): String {
        val code = Regex("-x-([a-z]+)").find(name)?.groupValues?.get(1)
        return when {
            code != null -> "Voice ${code.uppercase()}"
            name.endsWith("-language") -> "Standard"
            else -> name
        }
    }

    /**
     * Speaks [text] into [out] (WAV) with the voice named [name] (blank: the phone's best offline voice
     * for [locale]). Returns the word times, or an estimate when the phone gives no word events.
     * Blocks until done, so call it off the main thread.
     */
    fun synth(text: String, name: String, locale: String, out: File): List<WordTime> {
        if (!awaitReady()) throw IOException("The phone has no voice to read with.")
        val job = Job()
        val id = UUID.randomUUID().toString()
        synchronized(lock) {
            val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            val lang = locale.substringBefore('-').lowercase()
            val voice = voices.firstOrNull { it.name == name }
                ?: voices.filter { it.locale.language == lang && !it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
            if (voice != null) tts.voice = voice else tts.language = Locale.forLanguageTag(locale.ifBlank { "en-US" })
            tts.setSpeechRate(1f)
            tts.setPitch(1f)
            jobs[id] = job
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".part")
            if (tts.synthesizeToFile(text, Bundle(), tmp, id) != TextToSpeech.SUCCESS) {
                jobs.remove(id)
                throw IOException("The phone voice could not start.")
            }
            val finished = job.done.await(120, TimeUnit.SECONDS)
            jobs.remove(id)
            if (!finished || job.failed || !tmp.exists() || tmp.length() < 100) {
                tmp.delete()
                throw IOException(if (voice?.isNetworkConnectionRequired == true) "This voice needs the internet." else "The phone voice failed.")
            }
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
        }
        val header = Wav.read(out)
        val rate = if (job.rate > 0) job.rate else header?.rate ?: 0
        val words = job.words.toList()
        return if (rate > 0 && words.size >= 2) {
            words.mapNotNull { SpeechTimings.phoneWord(it, text.length, rate) }.sortedBy { it.at }
                .ifEmpty { SpeechTimings.estimateWords(text, header?.seconds ?: 0.0) }
        } else SpeechTimings.estimateWords(text, header?.seconds ?: 0.0)
    }

    /** Says a short sample out loud now, for the voice list. */
    fun preview(name: String, text: String) {
        Thread {
            if (!awaitReady()) return@Thread
            synchronized(lock) {
                tts.voices.orEmpty().firstOrNull { it.name == name }?.let { tts.voice = it }
                tts.setSpeechRate(1f)
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
            }
        }.start()
    }

    fun stopPreview() { runCatching { tts.stop() } }

    companion object {
        /**
         * Opens the phone's own page for getting more voices, where Google's offline voices are downloaded.
         * Returns false when the phone has no such page.
         */
        fun openVoiceDownloads(context: Context): Boolean {
            val tries = listOf(
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
                Intent("com.android.settings.TTS_SETTINGS"),
            )
            for (i in tries) {
                try {
                    context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return true
                } catch (_: ActivityNotFoundException) {
                } catch (_: SecurityException) {
                }
            }
            return false
        }
    }
}
