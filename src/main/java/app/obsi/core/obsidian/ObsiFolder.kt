package app.obsi.core.obsidian

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.obsi.core.Brand
import app.obsi.core.ObsiApp
import app.obsi.core.data.AppSettings

/**
 * Where the Obsi apps put their notes in your vault: one folder ("Obsi" unless you renamed it), with one
 * folder in it for each app. So the apps add one folder to the top of your vault, not one each.
 */
object ObsiFolder {
    const val DEFAULT = "Obsi"

    /** The one top folder for all Obsi apps. */
    fun root(s: AppSettings): String = s.obsiFolder.trim().trim('/').ifBlank { DEFAULT }

    /** The app's own folder name inside the top folder. */
    fun name(app: ObsiApp): String = when (app) {
        ObsiApp.Tube -> "ObsiTube"
        ObsiApp.Read -> "ObsiRead"
        ObsiApp.Learn -> "ObsiLearn"
        ObsiApp.Bible -> "Bible"
    }

    /** This app's folder, like "Obsi/ObsiRead". [sub] adds a folder under it. */
    fun of(s: AppSettings, sub: String = "", app: ObsiApp = Brand.app): String =
        listOf(root(s), name(app), sub.trim('/')).filter { it.isNotBlank() }.joinToString("/")

    /**
     * Moves every file from [from] into [to], both folders in the vault. [to] is made when it is missing.
     * A file whose name is already in [to] stays where it is, so nothing is written over. [from] is removed
     * when it is empty after. Returns how many files moved.
     */
    fun moveAll(context: Context, s: AppSettings, from: String, to: String): Int = runCatching {
        if (from.trim('/') == to.trim('/')) return 0
        val src = VaultNotes.folder(context, s, from, false) ?: return 0
        val files = src.listFiles().filter { it.isFile }
        if (files.isEmpty()) return 0
        val dst = VaultNotes.folder(context, s, to, true) ?: return 0
        var moved = 0
        for (f in files) {
            val name = f.name ?: continue
            if (dst.findFile(name) != null) continue
            if (move(context, f, src, dst)) moved++
        }
        if (src.listFiles().isEmpty()) src.delete()
        moved
    }.getOrDefault(0)

    /**
     * Moves one note into the vault's .trash folder, where Obsidian keeps deleted notes. It can be put back
     * from there. A note of the same name already in the trash gets a number. True when it moved.
     */
    fun trash(context: Context, s: AppSettings, folder: String, fileName: String): Boolean = runCatching {
        val dir = VaultNotes.folder(context, s, folder, false) ?: return false
        val f = dir.findFile(fileName)?.takeIf { it.isFile } ?: return false
        val bin = VaultNotes.folder(context, s, ".trash", true) ?: return false
        if (bin.findFile(fileName) != null) {
            val base = fileName.removeSuffix(".md")
            val free = (1..999).map { "$base $it.md" }.firstOrNull { bin.findFile(it) == null } ?: return false
            if (!f.renameTo(free)) return false
            val renamed = dir.findFile(free) ?: return false
            return move(context, renamed, dir, bin)
        }
        move(context, f, dir, bin)
    }.getOrDefault(false)

    /** Moves a file to another folder. Copies and then deletes when the phone's storage cannot move. */
    private fun move(context: Context, f: DocumentFile, from: DocumentFile, to: DocumentFile): Boolean {
        val r = context.contentResolver
        runCatching { DocumentsContract.moveDocument(r, f.uri, from.uri, to.uri) }.getOrNull()?.let { return true }
        val name = f.name ?: return false
        val copy = to.createFile("application/octet-stream", name) ?: return false
        val ok = runCatching {
            r.openInputStream(f.uri)!!.use { i -> r.openOutputStream(copy.uri, "w")!!.use { o -> i.copyTo(o) } }
            copy.length() == f.length()
        }.getOrDefault(false)
        if (!ok) { copy.delete(); return false }
        return f.delete()
    }
}
