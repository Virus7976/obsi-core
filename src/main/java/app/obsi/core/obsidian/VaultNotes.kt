package app.obsi.core.obsidian

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.obsi.core.data.AppSettings

/**
 * The notes an Obsi app keeps in your vault, so a screen can show them: which ones, where, how many.
 * Shared by all Obsi apps. Each note an app writes for itself has `type: obsi<app>-...` in its frontmatter.
 */
object VaultNotes {
    /** A folder in the linked vault. [create] makes the missing folders. Null when there is no vault folder. */
    fun folder(context: Context, s: AppSettings, path: String, create: Boolean): DocumentFile? {
        val name = s.linkedVault(s.vault) ?: return null
        val tree = s.vaultFolders[name] ?: return null
        var dir = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return null
        for (part in path.split("/").filter { it.isNotBlank() }) {
            dir = dir.findFile(part)?.takeIf { it.isDirectory } ?: (if (create) dir.createDirectory(part) else null) ?: return null
        }
        return dir
    }

    /** One note. [type] is its frontmatter type, [id] the app's own id for it, when it has them. */
    data class NoteFile(val name: String, val uri: String, val modified: Long, val size: Long, val type: String?, val id: String?)

    /** Every note in one vault folder, with the first lines of its frontmatter read. [idKey] names the key that holds the app's id. */
    fun list(context: Context, s: AppSettings, folder: String, idKey: String = ""): List<NoteFile> = runCatching {
        val dir = VaultNotes.folder(context, s, folder, false) ?: return emptyList()
        dir.listFiles().filter { it.isFile && it.name.orEmpty().endsWith(".md") }.map { f ->
            val head = context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { r -> List(12) { r.readLine() }.filterNotNull() }.orEmpty()
            fun key(k: String) = head.firstOrNull { it.startsWith("$k: ") }?.removePrefix("$k: ")?.trim()
            NoteFile(f.name.orEmpty().removeSuffix(".md"), f.uri.toString(), f.lastModified(), f.length(), key("type"), if (idKey.isBlank()) null else key(idKey))
        }.sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    fun delete(context: Context, uri: String): Boolean = runCatching {
        DocumentFile.fromSingleUri(context, Uri.parse(uri))?.delete() == true
    }.getOrDefault(false)

    /** Is there a vault folder to write to? */
    fun hasVault(s: AppSettings): Boolean = s.linkedVault(s.vault)?.let { s.vaultFolders[it] } != null

    /** The vault name, for opening notes in Obsidian. */
    fun vaultName(s: AppSettings): String = s.linkedVault(s.vault).orEmpty()

    /**
     * Adds the folder to Obsidian's Excluded files (.obsidian/app.json, userIgnoreFilters), or takes it off.
     * Excluded notes stay in the file list but leave search, the quick switcher and the graph.
     */
    fun setExcluded(context: Context, s: AppSettings, folder: String, on: Boolean): Boolean = runCatching {
        val name = s.linkedVault(s.vault) ?: return false
        val root = DocumentFile.fromTreeUri(context, Uri.parse(s.vaultFolders[name] ?: return false)) ?: return false
        val conf = root.findFile(".obsidian")?.takeIf { it.isDirectory } ?: return false
        val file = conf.findFile("app.json")
        val old = file?.let { f -> context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() } }.orEmpty().ifBlank { "{}" }
        val json = kotlinx.serialization.json.Json { prettyPrint = true }
        val obj = json.parseToJsonElement(old) as? kotlinx.serialization.json.JsonObject ?: return false
        val entry = folder.trim('/') + "/"
        val list = (obj["userIgnoreFilters"] as? kotlinx.serialization.json.JsonArray)?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }.orEmpty()
        val next = if (on) (list + entry).distinct() else list.filter { it != entry }
        if (next == list) return true
        val out = kotlinx.serialization.json.JsonObject(obj + ("userIgnoreFilters" to kotlinx.serialization.json.JsonArray(next.map { kotlinx.serialization.json.JsonPrimitive(it) })))
        val f = file ?: conf.createFile("application/json", "app.json") ?: return false
        context.contentResolver.openOutputStream(f.uri, "wt")!!.bufferedWriter().use { it.write(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), out)) }
        true
    }.getOrDefault(false)

}
