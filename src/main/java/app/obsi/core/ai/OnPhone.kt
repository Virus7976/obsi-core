package app.obsi.core.ai

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.obsi.core.Brand
import app.obsi.core.ObsiApp
import app.obsi.core.share.SharedHighlights
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A model the apps can download and run on the phone, with no service and no internet. */
enum class PhoneModel(
    val id: String,
    val title: String,
    val blurb: String,
    val url: String,
    val file: String,
    val bytes: Long,
    /** Memory the phone needs for it to run well. */
    val minRamGb: Int,
) {
    Qwen15(
        "qwen2.5-1.5b", "Qwen 2.5, small", "Good at short jobs like tags. Free to use.",
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", 1_600_000_000L, 6,
    );

    companion object {
        fun ofFile(name: String): PhoneModel? = entries.firstOrNull { it.file == name }
    }
}

/** What the phone itself can do, for the setup page. */
data class PhoneState(
    /** Gemini Nano, built into some newer phones: a [FeatureStatus], or -1 before it is checked. */
    val builtIn: Int = -1,
    /** Bytes of Gemini Nano fetched so far, while the phone gets it. */
    val builtInBytes: Long? = null,
    /** The model file the apps use, and the app that keeps it. Null: none. */
    val file: String? = null,
    val fileBytes: Long = 0,
    val fileApp: ObsiApp? = null,
    /** 0 to 1 while a model downloads. */
    val downloading: Float? = null,
    /** Why the last download or run did not work. */
    val problem: String? = null,
    val ramGb: Double = 0.0,
) {
    val builtInReady: Boolean get() = builtIn == FeatureStatus.AVAILABLE
    /** Something on the phone can answer now. */
    val ready: Boolean get() = builtInReady || file != null
}

/**
 * AI that runs on the phone: Gemini Nano when the phone has it built in, else a model file the
 * person downloaded. One file serves every Obsi app: the app that downloaded it lends it to the
 * others, so it is never kept four times.
 */
object OnPhone {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = _state.asStateFlow()

    private fun prefs(c: Context) = c.getSharedPreferences("obsi_on_phone", Context.MODE_PRIVATE)
    fun dir(c: Context): File = File(c.getExternalFilesDir(null) ?: c.filesDir, "models").apply { mkdirs() }

    /** The model file this app keeps, when it keeps one. Lent to the other apps through the provider. */
    fun ownFile(c: Context): File? = dir(c).listFiles()?.filter { it.isFile && it.name.endsWith(".litertlm") && it.length() > 0 }?.maxByOrNull { it.lastModified() }

    fun ramGb(c: Context): Double {
        val m = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(m)
        return m.totalMem / 1_073_741_824.0
    }

    /** The model that fits this phone, or null when the phone has too little memory for any. */
    fun suggest(c: Context): PhoneModel? = PhoneModel.entries.firstOrNull { ramGb(c) + 0.5 >= it.minRamGb }

    /** Looks at the phone again: Gemini Nano, the model file here, and the one another app lends. */
    suspend fun check(c: Context) = withContext(Dispatchers.IO) {
        val ctx = c.applicationContext
        val builtIn = runCatching { Generation.getClient().checkStatus() }.getOrDefault(FeatureStatus.UNAVAILABLE)
        val own = ownFile(ctx)
        val lent = if (own == null) lender(ctx) else null
        _state.update {
            it.copy(
                builtIn = builtIn, ramGb = ramGb(ctx),
                file = own?.name ?: lent?.second, fileBytes = own?.length() ?: lent?.third ?: 0,
                fileApp = if (own != null) Brand.app else lent?.first,
            )
        }
        Ai.recheck(ctx)
    }

    /** Another Obsi app that keeps a model file: the app, the file name and its size. */
    private fun lender(c: Context): Triple<ObsiApp, String, Long>? = Brand.others.filter { it.installed(c) }.firstNotNullOfOrNull { other ->
        runCatching {
            c.contentResolver.query(Uri.parse("content://${SharedHighlights.authority(other)}/model"), null, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) Triple(other, cur.getString(0), cur.getLong(1)) else null
            }
        }.getOrNull()
    }

    internal fun modelCursor(c: Context) = android.database.MatrixCursor(arrayOf("name", "bytes"), 1).apply {
        ownFile(c)?.let { addRow(arrayOf<Any>(it.name, it.length())) }
    }

    // ---------- getting a model ----------

    /** Asks the phone to fetch Gemini Nano. Only offered when the phone says it can. */
    fun getBuiltIn(c: Context) {
        val ctx = c.applicationContext
        scope.launch {
            runCatching {
                Generation.getClient().download().collect { s ->
                    when (s) {
                        is DownloadStatus.DownloadStarted -> _state.update { it.copy(builtInBytes = 0, problem = null) }
                        is DownloadStatus.DownloadProgress -> _state.update { it.copy(builtInBytes = s.totalBytesDownloaded) }
                        DownloadStatus.DownloadCompleted -> _state.update { it.copy(builtInBytes = null) }
                        is DownloadStatus.DownloadFailed -> _state.update { it.copy(builtInBytes = null, problem = "The phone could not get Gemini Nano. Try again later.") }
                    }
                }
            }.onFailure { e -> _state.update { it.copy(builtInBytes = null, problem = "The phone could not get Gemini Nano. ${e.message.orEmpty()}".trim()) } }
            check(ctx)
        }
    }

    /** Downloads [m] on Wi-Fi with the phone's own download manager, so it goes on after you leave the app. */
    fun download(c: Context, m: PhoneModel) {
        val ctx = c.applicationContext
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        File(dir(ctx), m.file).delete()
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle(m.title)
            .setDescription("AI model for the Obsi apps")
            .setDestinationUri(Uri.fromFile(File(dir(ctx), m.file + ".part")))
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val id = dm.enqueue(req)
        prefs(ctx).edit().putLong("download", id).putString("downloadFile", m.file).apply()
        _state.update { it.copy(downloading = 0f, problem = null) }
        watch(ctx)
    }

    private var watching: Job? = null

    /** Follows a download, also one started before the app was closed. */
    fun watch(c: Context) {
        val ctx = c.applicationContext
        val id = prefs(ctx).getLong("download", -1L)
        if (id < 0 || watching?.isActive == true) return
        watching = scope.launch {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (true) {
                val row = dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                    if (!cur.moveToFirst()) null else Triple(
                        cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    )
                }
                if (row == null) { finishDownload(ctx, "The download was stopped."); break }
                val (status, got, total) = row
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val name = prefs(ctx).getString("downloadFile", null)
                        val part = name?.let { File(dir(ctx), "$it.part") }
                        if (part != null && part.exists()) part.renameTo(File(dir(ctx), name))
                        finishDownload(ctx, null); break
                    }
                    DownloadManager.STATUS_FAILED -> { finishDownload(ctx, "The download did not finish. Check the Wi-Fi and try again."); break }
                    else -> _state.update { it.copy(downloading = if (total > 0) got.toFloat() / total else 0f) }
                }
                delay(1000)
            }
        }
    }

    private suspend fun finishDownload(c: Context, problem: String?) {
        prefs(c).edit().remove("download").remove("downloadFile").apply()
        _state.update { it.copy(downloading = null, problem = problem) }
        check(c)
    }

    fun cancelDownload(c: Context) {
        val id = prefs(c).getLong("download", -1L)
        if (id >= 0) (c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
        watching?.cancel()
        scope.launch { dir(c).listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }; finishDownload(c, null) }
    }

    /** Copies a model file the person already has (a .litertlm file) into the app. */
    suspend fun adopt(c: Context, uri: Uri, name: String) = withContext(Dispatchers.IO) {
        val ctx = c.applicationContext
        val clean = name.substringAfterLast('/').ifBlank { "model.litertlm" }
        if (!clean.endsWith(".litertlm", true)) throw AiException("Pick a .litertlm model file.")
        val part = File(dir(ctx), "$clean.part")
        _state.update { it.copy(downloading = 0f, problem = null) }
        try {
            val total = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
            ctx.contentResolver.openInputStream(uri)?.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20); var done = 0L
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) _state.update { it.copy(downloading = done.toFloat() / total) }
                    }
                }
            } ?: throw AiException("Could not open that file.")
            ownFile(ctx)?.delete()
            part.renameTo(File(dir(ctx), clean))
        } catch (e: Exception) {
            part.delete()
            _state.update { it.copy(problem = e.message ?: "Could not copy that file.") }
        } finally {
            _state.update { it.copy(downloading = null) }
            check(ctx)
        }
    }

    /** Removes the model file this app keeps. */
    suspend fun remove(c: Context) = withContext(Dispatchers.IO) {
        close()
        ownFile(c)?.delete()
        check(c)
    }

    // ---------- asking ----------

    private val lock = Mutex()
    private var engine: Engine? = null
    private var engineFor: String? = null
    private var lent: ParcelFileDescriptor? = null
    private var idle: Job? = null

    /** One question to the phone's own model. Gemini Nano first, when the phone has it, else the model file. */
    suspend fun ask(c: Context, system: String, user: String, maxTokens: Int): String = withContext(Dispatchers.IO) {
        val ctx = c.applicationContext
        val s = _state.value
        if (s.builtInReady) {
            runCatching {
                val r = Generation.getClient().generateContent(
                    generateContentRequest(TextPart("$system\n\n$user")) {
                        temperature = 0.2f
                        maxOutputTokens = maxTokens.coerceAtMost(256)
                    },
                )
                r.candidates.firstOrNull()?.text.orEmpty()
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        }
        if (s.file == null) throw AiException("No AI on this phone.")
        lock.withLock {
            val e = open(ctx, s)
            idle?.cancel()
            try {
                e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(system),
                        samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.2),
                    ),
                ).use { conv ->
                    conv.sendMessage(user).contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>().joinToString("") { it.text }
                }
            } finally {
                // The model holds a lot of memory. Let it go when it is not used for a while.
                idle = scope.launch { delay(90_000); lock.withLock { close() } }
            }
        }
    }

    private fun open(c: Context, s: PhoneState): Engine {
        val path = ownFile(c)?.absolutePath ?: run {
            // The file is in another Obsi app: it lends an open file, and the model reads it through that.
            val app = s.fileApp ?: throw AiException("No AI on this phone.")
            if (lent == null) lent = c.contentResolver.openFileDescriptor(Uri.parse("content://${SharedHighlights.authority(app)}/model"), "r")
            "/proc/self/fd/${lent?.fd ?: throw AiException("Could not open the model in ${app.title}.")}"
        }
        engine?.let { if (engineFor == path) return it }
        close()
        val cache = File(c.cacheDir, "litertlm").apply { mkdirs() }.path
        // The graphics chip is much faster. Some phones cannot use it, so the main chip is the fallback.
        val e = runCatching { Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = cache)).also { it.initialize() } }
            .recoverCatching { Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cache)).also { it.initialize() } }
            .getOrElse { err ->
                _state.update { it.copy(problem = "The model on this phone did not start. ${err.message.orEmpty()}".trim()) }
                throw AiException("The model on this phone did not start.")
            }
        engine = e; engineFor = path
        return e
    }

    private fun close() {
        runCatching { engine?.close() }
        engine = null; engineFor = null
        runCatching { lent?.close() }
        lent = null
    }
}
