package app.obsi.core.speech

import android.content.ComponentName
import app.obsi.core.WorkNotice
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Something to read out loud: an article, a note. [paras] are the paragraphs as the page shows them. */
data class ListenDoc(
    val id: String,
    val title: String,
    val source: String,
    val paras: List<String>,
    val locale: String = "en-US",
    /** What kind of text each part is (heading, caption, footnote number), so it can be skipped. */
    val marks: List<ListenMark> = emptyList(),
    /** The first word number of each paragraph, so the app can save the place you listened to. */
    val firstWords: List<Int> = emptyList(),
)

/** What the player bar and the listen sheet show. */
data class ListenUi(
    val active: Boolean = false,
    /** Read to the end, with nothing after it. */
    val ended: Boolean = false,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val docId: String = "",
    val title: String = "",
    val source: String = "",
    /** The paragraph being read, and the word in it as character offsets in that paragraph. */
    val para: Int = -1,
    val word: IntRange? = null,
    val line: Int = 0,
    val lines: Int = 0,
    val minutesLeft: Int = 0,
    val speed: Float = 1f,
    val voice: SpeakerVoice? = null,
    val error: String? = null,
    /** 0 = off, [SLEEP_END] = at the end of this one, else minutes. */
    val sleepMinutes: Int = 0,
    val sleepLeft: Long = 0L,
)

/** How much of one document's audio is kept on the phone for [voiceId]. */
data class KeepState(
    val docId: String,
    val voiceId: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val running: Boolean = false,
    val error: String? = null,
) {
    val complete get() = total > 0 && done >= total
    val partly get() = done in 1 until total
}

const val SLEEP_END = -1

/**
 * Read aloud for every Obsi app. One ExoPlayer plays every voice, so speed, seeking, the lock screen,
 * headphone buttons and the notification work the same way for all of them.
 *
 * The text is cut into pieces of whole sentences. Each piece is spoken into a short file just before it
 * plays (the player asks for the next one while the current one plays) and kept in a cache. A document
 * can be kept on the phone for good with [keep], so an online voice plays later with no internet.
 */
@OptIn(UnstableApi::class)
class ReadAloud private constructor(private val app: Context) {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    val prefs = ListenPrefs(app)
    val phone = PhoneVoices(app)

    private val cacheDir = File(app.cacheDir, "listen").apply { mkdirs() }
    private val keptRoot = File(app.filesDir, "listen/kept").apply { mkdirs() }

    /** One piece of text in the playlist. [offset] is where it starts in paragraph [para]. */
    private data class Line(val doc: String, val para: Int, val offset: Int, val text: String)

    @Volatile private var doc: ListenDoc? = null
    @Volatile private var lines: List<Line> = emptyList()
    @Volatile private var lineVoice: SpeakerVoice? = null
    private val words = ConcurrentHashMap<Int, List<WordTime>>()

    private val _ui = MutableStateFlow(ListenUi(speed = prefs.speed))
    val ui: StateFlow<ListenUi> = _ui.asStateFlow()

    private val _voices = MutableStateFlow<List<SpeakerVoice>>(emptyList())
    /** Every voice that can be picked now: the phone's, then Microsoft's when a key is set. */
    val voices: StateFlow<List<SpeakerVoice>> = _voices.asStateFlow()
    private val _voicesError = MutableStateFlow<String?>(null)
    val voicesError: StateFlow<String?> = _voicesError.asStateFlow()

    private val _keeps = MutableStateFlow<Map<String, KeepState>>(emptyMap())
    /** Offline audio, by document id. */
    val keeps: StateFlow<Map<String, KeepState>> = _keeps.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(LineSourceFactory()))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    private var controller: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var ticker: Job? = null
    private var sleepJob: Job? = null

    /** Opens a document in the app, from the notification. Each app sets it. */
    var opener: ((docId: String) -> Unit)? = null

    init {
        player.setPlaybackSpeed(prefs.speed)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _ui.update { it.copy(playing = isPlaying, ended = if (isPlaying) false else it.ended) }
                if (isPlaying) startTicker()
            }

            override fun onPlaybackStateChanged(state: Int) {
                _ui.update { it.copy(loading = state == Player.STATE_BUFFERING) }
                if (state == Player.STATE_ENDED) onEnded()
                tick()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = tick()

            override fun onPlayerError(error: PlaybackException) = onError(error)
        })
        refreshVoices()
    }

    // ---------- voices ----------

    /** Loads the voice lists again: after a Microsoft key is added, or new phone voices are downloaded. */
    fun refreshVoices() {
        io.launch {
            val phoneList = runCatching { phone.voices() }.getOrDefault(emptyList())
            var ms = emptyList<SpeakerVoice>()
            var err: String? = null
            if (prefs.msKey.isNotBlank() && prefs.msRegion.isNotBlank()) {
                ms = runCatching { MicrosoftVoices.voices(prefs.msKey, prefs.msRegion) }
                    .onFailure { err = it.message }.getOrDefault(prefs.msVoicesCache())
                if (ms.isNotEmpty()) prefs.saveMsVoices(ms)
            }
            // Edge voices: free, no key. The list is kept, so it shows with no internet too.
            val edge = runCatching { EdgeVoices.voices() }.onSuccess { if (it.isNotEmpty()) prefs.saveEdgeVoices(it) }
                .getOrElse { prefs.edgeVoicesCache() }
            _voices.value = phoneList + edge + ms
            _voicesError.value = err
            _ui.update { it.copy(voice = it.voice ?: pickVoice(doc?.locale ?: "en-US")) }
        }
    }

    /** The voice to read [locale] text with: the one picked, else the phone's best offline voice. */
    fun pickVoice(locale: String): SpeakerVoice? = resolve(prefs.lastVoiceId.ifBlank { prefs.voiceId }, locale)

    /** The default voice, from Settings. */
    fun defaultVoice(locale: String): SpeakerVoice? = resolve(prefs.voiceId, locale)

    /** The voice audio is saved with: the default, or the last one used, as set in Settings. */
    fun keepVoice(locale: String): SpeakerVoice? = if (prefs.saveWithLast) pickVoice(locale) else defaultVoice(locale)

    private fun resolve(id: String, locale: String): SpeakerVoice? {
        val all = _voices.value
        val lang = locale.substringBefore('-').lowercase()
        val chosen = all.firstOrNull { it.id == id }
        if (chosen != null && (chosen.kind != VoiceKind.MICROSOFT || prefs.msKey.isNotBlank())) return chosen
        // Nothing picked yet: a natural Edge voice, like Readest. With no internet it falls back to the phone.
        val edge = all.filter { it.kind == VoiceKind.EDGE && it.language == lang }
        val best = edge.firstOrNull { it.id == "edge:en-US-EmmaMultilingualNeural" && lang == "en" }
            ?: edge.firstOrNull { it.locale.equals(locale, true) && it.note.contains("Multilingual") }
            ?: edge.firstOrNull { it.locale.equals(locale, true) } ?: edge.firstOrNull()
        if (id.isBlank() && best != null) return best
        return all.firstOrNull { it.kind == VoiceKind.PHONE && it.language == lang }
            ?: all.firstOrNull { it.kind == VoiceKind.PHONE }
            ?: SpeakerVoice("phone:", "Phone voice", locale)
    }

    /** Sets the default voice (Settings). It is also the voice used from now on. */
    fun setDefaultVoice(v: SpeakerVoice) {
        prefs.voiceId = v.id
        setVoice(v)
    }

    /** Changes the voice while listening. It becomes the last voice used. */
    fun setVoice(v: SpeakerVoice) {
        prefs.lastVoiceId = v.id
        val d = doc
        val at = _ui.value.line
        _ui.update { it.copy(voice = v) }
        if (d != null && _ui.value.active) start(d, lines.getOrNull(at)?.para ?: 0, v, play = player.playWhenReady)
        d?.let { refreshKeep(it) }
    }

    private var sample: MediaPlayer? = null

    /** Says a short sample in [v]. Online voices need the internet for this. */
    fun preview(v: SpeakerVoice, text: String = "You dance, or you don't dance. Here is how I sound.") {
        stopPreview()
        when (v.kind) {
            VoiceKind.PHONE, VoiceKind.PHONE_ONLINE -> phone.preview(v.id.removePrefix("phone:"), text)
            VoiceKind.MICROSOFT, VoiceKind.EDGE -> io.launch {
                val f = File(cacheDir, "sample-${sha1(v.id)}.mp3")
                runCatching {
                    if (!f.exists()) {
                        if (v.kind == VoiceKind.EDGE) EdgeVoices.synth(v.id.removePrefix("edge:"), text, f)
                        else MicrosoftVoices.synth(prefs.msKey, prefs.msRegion, v.id.removePrefix("ms:"), text, f)
                    }
                    withContext(Dispatchers.Main) {
                        sample = MediaPlayer().apply { setDataSource(f.path); setOnPreparedListener { it.start() }; prepareAsync() }
                    }
                }.onFailure { e -> _ui.update { it.copy(error = e.message ?: "The sample did not play.") } }
            }
        }
    }

    fun stopPreview() {
        phone.stopPreview()
        sample?.let { runCatching { it.stop() }; it.release() }
        sample = null
    }

    // ---------- playing ----------

    /** Reads [d] from paragraph [fromPara]. If [d] is already playing, it jumps there instead. */
    fun play(d: ListenDoc, fromPara: Int = 0, fromChar: Int = 0) {
        val voice = pickVoice(d.locale) ?: return
        if (fromChar > 0) { start(d, fromPara, voice, play = true, fromChar = fromChar); return }
        if (_ui.value.active && doc?.id == d.id && doc?.paras == d.paras && lineVoice?.id == voice.id) {
            seekToPara(fromPara)
            if (!player.playWhenReady) player.play()
            return
        }
        start(d, fromPara, voice, play = true)
    }

    private fun start(d: ListenDoc, fromPara: Int, voice: SpeakerVoice, play: Boolean, fromChar: Int = 0) {
        val pieces = splitAt(cut(d), fromPara, fromChar)
        if (pieces.isEmpty()) { _ui.update { it.copy(error = "There is nothing to read here.") }; return }
        doc = d
        lines = pieces
        lineVoice = voice
        words.clear()
        val meta = MediaMetadata.Builder()
            .setTitle(d.title).setDisplayTitle(d.title)
            .setArtist(listOf(d.source, voice.name).filter { it.isNotBlank() }.joinToString(" · "))
            .setArtworkData(cover(d), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        val items = pieces.indices.map { i ->
            MediaItem.Builder().setUri(Uri.parse("listen://line/${d.id.hashCode()}/$i")).setMediaId("$i").setMediaMetadata(meta).build()
        }
        val first = pieces.indexOfFirst { it.para > fromPara || (it.para == fromPara && it.offset + it.text.length > fromChar) }.coerceAtLeast(0)
        player.setMediaItems(items, first, 0L)
        player.setPlaybackSpeed(prefs.speed)
        _ui.value = _ui.value.copy(
            active = true, docId = d.id, title = d.title, source = d.source, para = pieces[first].para, word = null,
            line = first, lines = pieces.size, voice = voice, error = null, loading = true, speed = prefs.speed,
            minutesLeft = minutesLeft(first),
        )
        player.prepare()
        if (play) player.play()
        connectSession()
    }

    private fun cut(d: ListenDoc): List<Line> =
        // A skipped part stays as spaces, so each line keeps its length and the lit word lines up.
        ListenSkip.apply(d, prefs.skip).flatMapIndexed { p, text -> SpeechTimings.pieces(text).map { (at, t) -> Line(d.id, p, at, t) } }

    /**
     * Pieces join several short sentences. To start at a word, the piece that holds it is split where that
     * word's sentence starts, so the voice begins with that sentence and not a few before it.
     */
    private fun splitAt(pieces: List<Line>, para: Int, char: Int): List<Line> {
        if (char <= 0) return pieces
        val i = pieces.indexOfFirst { it.para == para && char >= it.offset && char < it.offset + it.text.length }
        if (i < 0) return pieces
        val l = pieces[i]
        val rel = char - l.offset
        val cut = Regex("(?<=[.!?…][\"'”’)\\]]?)\\s+").findAll(l.text).map { it.range.last + 1 }.lastOrNull { it <= rel } ?: return pieces
        val head = l.text.substring(0, cut).trimEnd()
        val tail = l.text.substring(cut)
        if (head.isEmpty() || tail.isBlank()) return pieces
        return pieces.subList(0, i) + Line(l.doc, l.para, l.offset, head) + Line(l.doc, l.para, l.offset + cut, tail) + pieces.subList(i + 1, pieces.size)
    }

    private fun minutesLeft(line: Int): Int {
        val chars = lines.drop(line).sumOf { it.text.length }
        // About 15 letters a second at normal speed.
        return Math.ceil(chars / (15.0 * 60 * prefs.speed)).toInt()
    }

    private fun connectSession() {
        if (controller != null) return
        controller = MediaController.Builder(app, SessionToken(app, ComponentName(app, ReadAloudService::class.java))).buildAsync()
    }

    fun toggle() {
        if (!_ui.value.active) return
        when {
            player.isPlaying -> player.pause()
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0, 0); player.play() }
            player.playbackState == Player.STATE_IDLE -> { player.prepare(); player.play() }
            else -> player.play()
        }
    }

    fun pause() = player.pause()

    fun stop() {
        sleepJob?.cancel()
        player.stop()
        player.clearMediaItems()
        doc = null
        lines = emptyList()
        _ui.update { ListenUi(speed = it.speed, voice = it.voice) }
    }

    /** Next sentence. */
    /** Next sentence. On the last one, the next document in the app's queue. */
    fun next() {
        if (!_ui.value.active) return
        val i = player.currentMediaItemIndex
        if (i + 1 < player.mediaItemCount) player.seekTo(i + 1, 0)
        else doc?.let { advance(it, skipped = true) }
    }

    /** Back a sentence. Late in a sentence, back to its start first, like a music player. */
    fun previous() {
        if (!_ui.value.active) return
        val i = player.currentMediaItemIndex
        if (player.currentPosition > 2500 || i == 0) player.seekTo(i, 0) else player.seekTo(i - 1, 0)
    }

    fun seekToPara(p: Int) {
        val i = lines.indexOfFirst { it.para >= p }
        if (i >= 0) player.seekTo(i, 0)
        tick()
    }

    /** Jump to sentence [i] (the progress slider). */
    fun seekToLine(i: Int) {
        if (!_ui.value.active || player.mediaItemCount == 0) return
        player.seekTo(i.coerceIn(0, player.mediaItemCount - 1), 0)
        tick()
    }

    fun setSpeed(x: Float) {
        val s = x.coerceIn(0.5f, 3f)
        prefs.speed = s
        player.setPlaybackSpeed(s)
        _ui.update { it.copy(speed = s, minutesLeft = minutesLeft(it.line)) }
    }

    /** 0 turns the timer off. [SLEEP_END] stops at the end of this document. */
    fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        _ui.update { it.copy(sleepMinutes = minutes, sleepLeft = if (minutes > 0) minutes * 60_000L else 0L) }
        if (minutes <= 0) return
        sleepJob = main.launch {
            val end = System.currentTimeMillis() + minutes * 60_000L
            while (isActive) {
                val left = end - System.currentTimeMillis()
                _ui.update { it.copy(sleepLeft = left.coerceAtLeast(0)) }
                if (left <= 0) break
                delay(1000)
            }
            for (i in 10 downTo 0) { player.volume = i / 10f; delay(250) }
            player.pause()
            player.volume = 1f
            _ui.update { it.copy(sleepMinutes = 0, sleepLeft = 0) }
        }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    /** The document being read now, if any. */
    val current: ListenDoc? get() = doc

    /**
     * Set by an app with a queue. Called when [finished] has been read to the end. It gives back what to read
     * next and the paragraph to start at, or null to stop.
     */
    var upNext: (suspend (finished: String) -> Pair<ListenDoc, Int>?)? = null

    /** Set by an app to save where you are: called with the document and the first word of each new paragraph. */
    var onPlace: ((docId: String, word: Int) -> Unit)? = null

    private fun onEnded() {
        val stopHere = _ui.value.sleepMinutes == SLEEP_END
        _ui.update { it.copy(playing = false, ended = true, minutesLeft = 0, sleepMinutes = if (it.sleepMinutes == SLEEP_END) 0 else it.sleepMinutes) }
        val d = doc ?: return
        if (stopHere) return
        advance(d, skipped = false)
    }

    /** Goes on to what the app's queue has next. [skipped]: Next was pressed on the last sentence. */
    private fun advance(d: ListenDoc, skipped: Boolean) {
        val next = upNext ?: return
        main.launch {
            val (n, para) = withContext(Dispatchers.IO) { runCatching { next(d.id) }.getOrNull() } ?: return@launch
            // Something else may have started while the next one loaded.
            if (doc?.id != d.id || (!skipped && player.playbackState != Player.STATE_ENDED)) return@launch
            val v = pickVoice(n.locale) ?: return@launch
            start(n, para, v, play = true)
        }
    }

    private fun onError(e: PlaybackException) {
        val cause = generateSequence(e as Throwable) { it.cause }.firstOrNull { it is IOException && !it.message.isNullOrBlank() }?.message
        val d = doc
        val v = lineVoice
        // An online voice failed (no internet, a wrong key): carry on with the phone's own voice.
        if (d != null && v != null && v.online) {
            val fallback = _voices.value.firstOrNull { it.kind == VoiceKind.PHONE && it.language == v.language }
                ?: _voices.value.firstOrNull { it.kind == VoiceKind.PHONE }
            if (fallback != null) {
                val at = lines.getOrNull(player.currentMediaItemIndex)?.para ?: 0
                start(d, at, fallback, play = true)
                _ui.update { it.copy(error = (cause ?: "${v.name} did not answer.") + " Reading with ${fallback.name} for now.") }
                return
            }
        }
        _ui.update { it.copy(error = cause ?: "Read aloud stopped.", playing = false, loading = false) }
    }

    // ---------- following along ----------

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = main.launch {
            while (isActive) {
                tick()
                if (!player.isPlaying) break
                delay(50)
            }
        }
    }

    private fun tick() {
        val u = _ui.value
        if (!u.active) return
        val i = player.currentMediaItemIndex
        val line = lines.getOrNull(i) ?: return
        val t = player.currentPosition / 1000.0
        var ws = words[i]
        if (ws.isNullOrEmpty()) {
            val dur = player.duration.takeIf { it > 0 && player.currentMediaItemIndex == i }?.div(1000.0)
            if (dur != null) ws = SpeechTimings.estimateWords(line.text, dur).also { words[i] = it }
        }
        val w = ws?.let { SpeechTimings.wordAt(it, t) }
        val range = w?.let { (line.offset + it.start) until (line.offset + it.end) }
        if (u.para != line.para) doc?.let { d -> d.firstWords.getOrNull(line.para)?.let { w -> onPlace?.invoke(d.id, w) } }
        if (u.line != i || u.para != line.para || u.word != range) {
            _ui.value = u.copy(line = i, para = line.para, word = range, minutesLeft = if (u.line != i) minutesLeft(i) else u.minutesLeft)
        }
    }

    // ---------- audio files ----------

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun keptDir(docId: String) = File(keptRoot, sha1(docId).take(16))

    private fun ext(v: SpeakerVoice) = if (v.kind == VoiceKind.MICROSOFT || v.kind == VoiceKind.EDGE) "mp3" else "wav"

    /** The audio for one piece of text: from what was kept, from the cache, or made now. */
    private fun audio(docId: String, text: String, v: SpeakerVoice): Pair<File, List<WordTime>?> {
        val name = sha1("${v.id}|$text")
        for (dir in listOf(keptDir(docId), cacheDir)) {
            for (e in listOf("m4a", "mp3", "wav")) {
                val f = File(dir, "$name.$e")
                if (f.exists() && f.length() > 0) return f to readWords(File(dir, "$name.json"))
            }
        }
        val out = File(cacheDir, "$name.${ext(v)}")
        val ws = make(text, v, out)
        if (ws != null) File(cacheDir, "$name.json").writeText(json.encodeToString(ListSerializer(WordTime.serializer()), ws))
        trimCache()
        return out to ws
    }

    private fun make(text: String, v: SpeakerVoice, out: File): List<WordTime>? = when (v.kind) {
        VoiceKind.PHONE, VoiceKind.PHONE_ONLINE -> phone.synth(text, v.id.removePrefix("phone:"), v.locale, out)
        VoiceKind.MICROSOFT -> {
            if (prefs.msKey.isBlank()) throw IOException("Add your Microsoft key to use this voice.")
            MicrosoftVoices.synth(prefs.msKey, prefs.msRegion, v.id.removePrefix("ms:"), text, out)
            null
        }
        VoiceKind.EDGE -> SpeechTimings.placeWords(text, EdgeVoices.synth(v.id.removePrefix("edge:"), text, out)).ifEmpty { null }
    }

    private fun readWords(f: File): List<WordTime>? =
        if (!f.exists()) null else runCatching { json.decodeFromString(ListSerializer(WordTime.serializer()), f.readText()) }.getOrNull()

    private fun trimCache() {
        val files = cacheDir.listFiles().orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 300L * 1024 * 1024) break
            total -= f.length()
            f.delete()
        }
    }

    /** Sends listen:// to the voice maker; the player then reads the file. */
    private inner class LineSourceFactory : DataSource.Factory {
        override fun createDataSource(): DataSource = LineSource()
    }

    private inner class LineSource : DataSource {
        private val file = FileDataSource()
        override fun addTransferListener(transferListener: TransferListener) = file.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            // Runs on the player's loading thread, so making the audio here is allowed.
            val i = dataSpec.uri.lastPathSegment?.toIntOrNull() ?: throw IOException("Unknown piece.")
            val line = lines.getOrNull(i) ?: throw IOException("Nothing to say.")
            val v = lineVoice ?: throw IOException("No voice.")
            val (f, ws) = audio(line.doc, line.text, v)
            if (!ws.isNullOrEmpty()) words[i] = ws
            return file.open(dataSpec.buildUpon().setUri(Uri.fromFile(f)).build())
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = file.read(buffer, offset, length)
        override fun getUri(): Uri? = file.uri
        override fun close() = file.close()
    }

    // ---------- keeping audio offline ----------

    private val keepLock = Mutex()
    private val keepJobs = ConcurrentHashMap<String, Job>()

    /** Counts how much of [d] is kept for the current voice. */
    fun refreshKeep(d: ListenDoc) {
        io.launch {
            val v = keepVoice(d.locale) ?: return@launch
            val pieces = cut(d)
            val dir = keptDir(d.id)
            val done = pieces.count { p -> val n = sha1("${v.id}|${p.text}"); listOf("m4a", "mp3", "wav").any { File(dir, "$n.$it").exists() } }
            _keeps.update { it + (d.id to (it[d.id] ?: KeepState(d.id)).copy(voiceId = v.id, done = done, total = pieces.size)) }
        }
    }

    /**
     * Makes and keeps every piece of [d] for the current voice, so it plays with no internet.
     * Phone voices that work offline need nothing kept. One document at a time, in the background.
     */
    fun keep(d: ListenDoc) {
        val v = keepVoice(d.locale) ?: return
        if (keepJobs[d.id]?.isActive == true) return
        prefs.queue(d.id, true)
        keepJobs[d.id] = io.launch {
            keepLock.withLock {
                val pieces = cut(d)
                val dir = keptDir(d.id).apply { mkdirs() }
                File(dir, "doc.txt").writeText(d.id)
                var done = 0
                var made = 0
                val tag = "keep:${d.id}"
                _keeps.update { it + (d.id to KeepState(d.id, v.id, 0, pieces.size, running = true)) }
                try {
                    for (p in pieces) {
                        val n = sha1("${v.id}|${p.text}")
                        val have = listOf("m4a", "mp3", "wav").any { File(dir, "$n.$it").exists() }
                        if (!have) {
                            WorkNotice.progress(app, tag, "Saving audio", "${d.title} · ${done + 1} of ${pieces.size}", done, pieces.size)
                            made++
                            if (prefs.wifiOnly && onMobileData()) throw IOException("Waiting for Wi-Fi.")
                            // A dropped connection is common on a phone: try again a few times before giving up.
                            var tries = 0
                            val (src, ws) = run {
                                while (true) {
                                    try { return@run audio(d.id, p.text, v) } catch (e: IOException) {
                                        if (++tries >= 4) throw e
                                        delay(2000L * tries)
                                    }
                                }
                                @Suppress("UNREACHABLE_CODE") error("")
                            }
                            if (src.extension == "wav") {
                                runCatching { AudioPack.wavToM4a(src, File(dir, "$n.m4a")) }
                                    .onFailure { src.copyTo(File(dir, "$n.wav"), overwrite = true) }
                            } else src.copyTo(File(dir, "$n.${src.extension}"), overwrite = true)
                            ws?.let { File(dir, "$n.json").writeText(json.encodeToString(ListSerializer(WordTime.serializer()), it)) }
                        }
                        done++
                        _keeps.update { it + (d.id to KeepState(d.id, v.id, done, pieces.size, running = true)) }
                    }
                    prefs.queue(d.id, false)
                    _keeps.update { it + (d.id to KeepState(d.id, v.id, done, pieces.size, running = false)) }
                    // Only work that took time gets a "saved" notice. Audio that was already there needs none.
                    if (made > 0) WorkNotice.done(app, tag, "Audio saved", "${d.title} plays with no internet.")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) { WorkNotice.cancel(app, tag); throw e }
                    _keeps.update { it + (d.id to KeepState(d.id, v.id, done, pieces.size, running = false, error = keepError(e))) }
                    WorkNotice.failed(app, tag, "Audio not saved", "${d.title}: ${keepError(e).substringBefore(" Tap")}")
                }
            }
        }
    }

    private fun keepError(e: Exception): String = when {
        e is kotlinx.coroutines.CancellationException -> "Stopped."
        e.message == "Waiting for Wi-Fi." -> "Waiting for Wi-Fi. It goes on by itself."
        e is java.net.UnknownHostException || e is java.net.SocketException || e is java.net.SocketTimeoutException ->
            "No connection. Tap Finish to go on."
        else -> (e.message ?: "Could not save the audio.") + " Tap Finish to try again."
    }

    /** Keeps [d] when the person asked for audio with everything they save, and the voice needs the internet. */
    fun keepIfWanted(d: ListenDoc) {
        if (!prefs.keepWithSaves) return
        io.launch { voicesReady(); if (keepVoice(d.locale)?.online == true) keep(d) }
    }

    /** At app start the voice list is still loading. Waits for it, but not for ever. */
    private suspend fun voicesReady() {
        kotlinx.coroutines.withTimeoutOrNull(15_000) { _voices.first { it.isNotEmpty() } }
    }

    /** Starts again the documents that were still being kept when the app closed. */
    fun resumeKeeps(load: suspend (String) -> ListenDoc?) {
        io.launch { voicesReady(); for (id in prefs.queued()) load(id)?.let { keep(it) } ?: prefs.queue(id, false) }
    }

    fun stopKeep(docId: String) {
        keepJobs.remove(docId)?.cancel()
        WorkNotice.cancel(app, "keep:$docId")
        prefs.queue(docId, false)
        _keeps.update { m -> m[docId]?.let { m + (docId to it.copy(running = false)) } ?: m }
    }

    /** Deletes the kept audio of one document. */
    fun removeKept(docId: String) {
        stopKeep(docId)
        keptDir(docId).deleteRecursively()
        _keeps.update { m -> m[docId]?.let { m + (docId to it.copy(done = 0, error = null)) } ?: m }
    }

    private fun onMobileData(): Boolean {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    /** Deletes every bit of kept audio. */
    fun removeAllKept() {
        keepJobs.values.forEach { it.cancel() }
        keepJobs.clear()
        prefs.queued().forEach { prefs.queue(it, false) }
        keptRoot.listFiles().orEmpty().forEach { it.deleteRecursively() }
        _keeps.value = emptyMap()
    }

    /** How many articles have audio on the phone. */
    fun keptCount(): Int = keptRoot.listFiles().orEmpty().count { d -> d.isDirectory && d.listFiles().orEmpty().any { it.extension in setOf("m4a", "mp3", "wav") } }

    /** Bytes of kept audio, for Settings. */
    fun keptBytes(docId: String? = null): Long =
        (if (docId != null) keptDir(docId).walkTopDown() else keptRoot.walkTopDown()).filter { it.isFile }.sumOf { it.length() }

    // ---------- the picture on the lock screen ----------

    private fun cover(d: ListenDoc): ByteArray {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0x14, 0x14, 0x16))
        val letter = (d.source.ifBlank { d.title }).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "“"
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x55, 0x55, 0x5A); typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL); textSize = 220f; textAlign = Paint.Align.CENTER }
        c.drawText(letter, size / 2f, 300f, big)
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x80, 0x80, 0x86); typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC); textSize = 36f; textAlign = Paint.Align.CENTER }
        var name = d.source.ifBlank { "" }
        while (name.isNotEmpty() && small.measureText(name) > size - 60) name = name.dropLast(2) + "…"
        c.drawText(name, size / 2f, 380f, small)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    companion object {
        @Volatile private var one: ReadAloud? = null

        /** The app's one read-aloud player. Call on the main thread. */
        fun get(context: Context): ReadAloud = one ?: synchronized(this) { one ?: ReadAloud(context.applicationContext).also { one = it } }

        /** Only when it was made already: the media service must not make one by itself in a cold app. */
        fun peek(): ReadAloud? = one
    }
}

/** Read-aloud choices, kept on this phone. */
class ListenPrefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("obsi_listen", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** The default voice, set in Settings. Blank: a natural Edge voice, else the phone's. */
    var voiceId: String
        get() = p.getString("voice", "") ?: ""
        set(v) = p.edit().putString("voice", v).apply()
    /** The voice last picked in the player. Blank: the default. */
    var lastVoiceId: String
        get() = p.getString("lastVoice", "") ?: ""
        set(v) = p.edit().putString("lastVoice", v).apply()
    /** Save audio only on Wi-Fi, not on mobile data. */
    var wifiOnly: Boolean
        get() = p.getBoolean("wifiOnly", false)
        set(v) = p.edit().putBoolean("wifiOnly", v).apply()
    /** Light up the word being read. */
    var highlightWords: Boolean
        get() = p.getBoolean("highlightWords", true)
        set(v) = p.edit().putBoolean("highlightWords", v).apply()
    /** Scroll the page with the voice. */
    var followAlong: Boolean
        get() = p.getBoolean("followAlong", true)
        set(v) = p.edit().putBoolean("followAlong", v).apply()
    /** The parts of a page that are not read, by [SkipPart.key]. */
    var skip: Set<String>
        get() = p.getStringSet("skip", null) ?: SkipPart.entries.filter { it.default }.map { it.key }.toSet()
        set(v) = p.edit().putStringSet("skip", v).apply()
    /** Save audio with the last voice used (true) or the default voice (false). */
    var saveWithLast: Boolean
        get() = p.getBoolean("saveWithLast", false)
        set(v) = p.edit().putBoolean("saveWithLast", v).apply()
    var speed: Float
        get() = p.getFloat("speed", 1f)
        set(v) = p.edit().putFloat("speed", v).apply()
    /** Keep audio with each saved article when the voice needs the internet. */
    var keepWithSaves: Boolean
        get() = p.getBoolean("keepWithSaves", true)
        set(v) = p.edit().putBoolean("keepWithSaves", v).apply()
    var msKey: String
        get() = p.getString("msKey", "") ?: ""
        set(v) = p.edit().putString("msKey", v.trim()).apply()
    var msRegion: String
        get() = p.getString("msRegion", "") ?: ""
        set(v) = p.edit().putString("msRegion", v.trim().lowercase()).apply()

    fun msVoicesCache(): List<SpeakerVoice> = runCatching {
        json.decodeFromString(ListSerializer(SpeakerVoice.serializer()), p.getString("msVoices", "[]") ?: "[]")
    }.getOrDefault(emptyList())

    fun saveMsVoices(v: List<SpeakerVoice>) = p.edit().putString("msVoices", json.encodeToString(ListSerializer(SpeakerVoice.serializer()), v)).apply()

    fun edgeVoicesCache(): List<SpeakerVoice> = runCatching {
        json.decodeFromString(ListSerializer(SpeakerVoice.serializer()), p.getString("edgeVoices", "[]") ?: "[]")
    }.getOrDefault(emptyList())

    fun saveEdgeVoices(v: List<SpeakerVoice>) = p.edit().putString("edgeVoices", json.encodeToString(ListSerializer(SpeakerVoice.serializer()), v)).apply()

    fun queued(): Set<String> = p.getStringSet("keepQueue", emptySet()).orEmpty()
    @Synchronized fun queue(id: String, on: Boolean) {
        val s = queued().toMutableSet()
        if (on) s += id else s -= id
        p.edit().putStringSet("keepQueue", s).apply()
    }
}
