package app.obsi.core.share

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.obsi.core.Brand
import app.obsi.core.ObsiApp
import app.obsi.core.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Obsidian setup one app can hand to the others: which vault, its folder, and your tags. */
data class ObsiSetup(
    val app: ObsiApp,
    val vault: String,
    /** The vault folder, as the folder picker gave it to that app. */
    val folder: String,
    val tags: List<String> = emptyList(),
    val autoTags: List<String> = emptyList(),
)

/**
 * Set up Obsidian once. Each app hands its setup to the other two through the same guarded
 * provider as the highlights. Android does not let one app pass on its folder access, so the
 * other app opens the folder picker right at that folder: one tap to say yes.
 */
object SharedSetup {
    /** This app's setup, or null when it has no vault folder yet. Set by the app. */
    @Volatile
    var source: (() -> ObsiSetup?)? = null

    private val columns = arrayOf("vault", "folder", "tags", "auto_tags")
    private const val SEP = "\u001f"

    internal fun cursor(): Cursor = MatrixCursor(columns, 1).apply {
        runCatching { source?.invoke() }.getOrNull()?.takeIf { it.folder.isNotBlank() }?.let {
            addRow(arrayOf<Any>(it.vault, it.folder, it.tags.joinToString(SEP), it.autoTags.joinToString(SEP)))
        }
    }

    /**
     * Other Obsi apps that are on the phone but will not share with this one. Android decides this when an
     * app is installed: an app put on before the others knew it stays locked out until it is installed again.
     */
    suspend fun lockedOut(context: Context): List<ObsiApp> = withContext(Dispatchers.IO) {
        Brand.others.filter { it.installed(context) }.filter { other ->
            try {
                context.contentResolver.query(Uri.parse("content://${SharedHighlights.authority(other)}/setup"), null, null, null, null)?.close()
                false
            } catch (_: SecurityException) { true } catch (_: Exception) { false }
        }
    }

    /** The setups of the other Obsi apps that have a vault folder. */
    suspend fun fromOthers(context: Context): List<ObsiSetup> = withContext(Dispatchers.IO) {
        Brand.others.filter { it.installed(context) }.mapNotNull { other -> read(context, other) }
    }

    private fun read(context: Context, from: ObsiApp): ObsiSetup? = runCatching {
        val uri = Uri.parse("content://${SharedHighlights.authority(from)}/setup")
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n)).orEmpty()
            ObsiSetup(from, s("vault"), s("folder"), s("tags").split(SEP).filter { it.isNotBlank() }, s("auto_tags").split(SEP).filter { it.isNotBlank() })
        }
    }.getOrNull()

    /** ObsiTube and ObsiRead: the setup in their shared settings. */
    fun of(s: AppSettings): ObsiSetup? {
        val name = s.vault.takeIf { it in s.vaultFolders } ?: s.vaultFolders.keys.firstOrNull() ?: return null
        return ObsiSetup(Brand.app, name, s.vaultFolders[name].orEmpty(), s.recentTags, s.autoTags)
    }

    /** ObsiTube and ObsiRead: takes in a setup, with the folder this app was just given. */
    fun apply(s: AppSettings, setup: ObsiSetup, folder: String): AppSettings = s.copy(
        vaults = (s.vaults + setup.vault).distinct(),
        vault = setup.vault,
        vaultFolders = s.vaultFolders + (setup.vault to folder),
        recentTags = (s.recentTags + setup.tags).distinct().take(40),
        autoTags = s.autoTags.ifEmpty { setup.autoTags },
    )
}
