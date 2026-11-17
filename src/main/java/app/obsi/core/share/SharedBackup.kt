package app.obsi.core.share

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.obsi.core.Brand
import app.obsi.core.ObsiApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One backup file for every Obsi app on the phone: settings, libraries, highlights, review, stats and
 * notes. Downloads (videos, Bibles, speech models) are left out, since they can be fetched again.
 *
 * The app that makes the backup asks each other Obsi app for its part through the share door, so the
 * file holds them all. Putting it back sends each app its own part; those apps then restart.
 */
object SharedBackup {
    /** Folders that only hold downloads or things built again by themselves. */
    private val skipDirs = setOf("offline", "offline-files", "speech", "bibles", "audio", "online", "text-index", "cache", "pdf-pictures")
    private const val MAX_FILE = 40L * 1024 * 1024
    private const val MANIFEST = "obsi-backup.txt"

    /** What a backup or a restore did, for people. */
    class Result(val apps: List<ObsiApp>, val files: Int, val bytes: Long)

    // ---------- one app's own part ----------

    private fun files(context: Context): List<Pair<String, File>> {
        val root = context.filesDir
        val out = ArrayList<Pair<String, File>>()
        fun walk(dir: File, rel: String) {
            dir.listFiles()?.forEach { f ->
                val name = rel + f.name
                if (f.isDirectory) { if (f.name !in skipDirs) walk(f, "$name/") }
                else if (f.length() <= MAX_FILE) out += name to f
            }
        }
        walk(root, "")
        return out
    }

    /** Writes this app's part as a zip. */
    fun exportOwn(context: Context, out: OutputStream): Int {
        var n = 0
        ZipOutputStream(out).use { z ->
            files(context).forEach { (name, f) ->
                z.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
                n++
            }
        }
        return n
    }

    /** Puts this app's part back. Files in the backup replace the ones here; others are kept. */
    fun importOwn(context: Context, input: InputStream): Int {
        val root = context.filesDir.canonicalFile
        var n = 0
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val target = File(root, e.name).canonicalFile
                // Never write outside the app's own folder.
                if (!target.path.startsWith(root.path + File.separator)) continue
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".restoring")
                tmp.outputStream().use { z.copyTo(it) }
                if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
                n++
            }
        }
        return n
    }

    /** Ends this app, so it starts again with what was put back. */
    fun restartSoon() {
        Thread { Thread.sleep(400); android.os.Process.killProcess(android.os.Process.myPid()) }.start()
    }

    // ---------- the share door ----------

    /** Opens this app's part for another Obsi app: "backup" to read it, "restore" to write it back. */
    internal fun open(context: Context, what: String, mode: String): ParcelFileDescriptor? {
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        return when {
            what == "backup" && mode.startsWith("r") -> {
                Thread {
                    runCatching {
                        ParcelFileDescriptor.AutoCloseOutputStream(write).use { exportOwn(context, it) }
                    }.onFailure { runCatching { write.closeWithError(it.message ?: "backup failed") } }
                }.start()
                read
            }
            what == "restore" && mode.contains("w") -> {
                Thread {
                    val ok = runCatching { ParcelFileDescriptor.AutoCloseInputStream(read).use { importOwn(context, it) } }.isSuccess
                    if (ok) restartSoon()
                }.start()
                write
            }
            else -> { read.close(); write.close(); null }
        }
    }

    private fun door(app: ObsiApp, what: String) = Uri.parse("content://${SharedHighlights.authority(app)}/$what")

    // ---------- every app at once ----------

    /** Writes one file with every Obsi app's part, each in a folder named after the app. */
    suspend fun backupAll(context: Context, to: Uri): Result = withContext(Dispatchers.IO) {
        val apps = ArrayList<ObsiApp>()
        var files = 0
        var bytes = 0L
        context.contentResolver.openOutputStream(to, "wt")!!.use { raw ->
            ZipOutputStream(raw).use { z ->
                fun add(app: ObsiApp, input: InputStream) {
                    var any = false
                    ZipInputStream(input).use { inner ->
                        while (true) {
                            val e = inner.nextEntry ?: break
                            z.putNextEntry(ZipEntry(app.name + "/" + e.name).apply { time = e.time })
                            bytes += inner.copyTo(z)
                            z.closeEntry()
                            files++; any = true
                        }
                    }
                    if (any) apps += app
                }
                // This app's part goes through a pipe too, so every part is handled the same way.
                val own = java.io.ByteArrayOutputStream()
                exportOwn(context, own)
                add(Brand.app, own.toByteArray().inputStream())
                Brand.others.filter { it.installed(context) }.forEach { other ->
                    runCatching { context.contentResolver.openInputStream(door(other, "backup"))?.use { add(other, it) } }
                }
                z.putNextEntry(ZipEntry(MANIFEST))
                z.write(("Obsi backup\nmade ${java.time.LocalDateTime.now()}\nby ${Brand.app.title}\napps ${apps.joinToString(",") { it.name }}\n").toByteArray())
                z.closeEntry()
            }
        }
        Result(apps, files, bytes)
    }

    /** The apps a backup file holds. Empty when it is not an Obsi backup. */
    suspend fun appsIn(context: Context, from: Uri): List<ObsiApp> = withContext(Dispatchers.IO) {
        runCatching {
            val found = LinkedHashSet<ObsiApp>()
            var isBackup = false
            context.contentResolver.openInputStream(from)!!.use { raw ->
                ZipInputStream(raw).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.name == MANIFEST) isBackup = true
                        ObsiApp.entries.firstOrNull { e.name.startsWith(it.name + "/") }?.let { found += it }
                    }
                }
            }
            if (isBackup) found.toList() else emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * Puts every app's part back. Other apps get theirs through the share door and restart. An app that is
     * not on the phone is skipped. This app's part goes in last; then call [restartSoon].
     */
    suspend fun restoreAll(context: Context, from: Uri): Result = withContext(Dispatchers.IO) {
        // Split the file into one zip per app first, so each app gets one stream.
        val parts = LinkedHashMap<ObsiApp, java.io.ByteArrayOutputStream>()
        val zips = HashMap<ObsiApp, ZipOutputStream>()
        var files = 0
        var bytes = 0L
        context.contentResolver.openInputStream(from)!!.use { raw ->
            ZipInputStream(raw).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val app = ObsiApp.entries.firstOrNull { e.name.startsWith(it.name + "/") } ?: continue
                    val out = zips.getOrPut(app) { ZipOutputStream(parts.getOrPut(app) { java.io.ByteArrayOutputStream() }) }
                    out.putNextEntry(ZipEntry(e.name.removePrefix(app.name + "/")).apply { time = e.time })
                    bytes += z.copyTo(out)
                    out.closeEntry()
                    files++
                }
            }
        }
        zips.values.forEach { it.close() }
        val done = ArrayList<ObsiApp>()
        parts.forEach { (app, bytesOut) ->
            if (app == Brand.app) return@forEach
            if (!app.installed(context)) return@forEach
            val ok = runCatching {
                context.contentResolver.openOutputStream(door(app, "restore"), "w")!!.use { it.write(bytesOut.toByteArray()) }
            }.isSuccess
            if (ok) done += app
        }
        parts[Brand.app]?.let { importOwn(context, it.toByteArray().inputStream()); done += Brand.app }
        Result(done, files, bytes)
    }
}
