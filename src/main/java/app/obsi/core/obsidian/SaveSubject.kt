package app.obsi.core.obsidian

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.obsi.core.data.AppSettings
import app.obsi.core.data.ClipMemo
import app.obsi.core.data.ClipTemplate

/** What adding to a note that already exists changed. */
data class MergeResult(val text: String, val addedHighlights: Int, val addedProps: Int) {
    val changed: Boolean get() = addedHighlights > 0 || addedProps > 0
}

/**
 * The video or article the save sheet is saving. Each app makes one, so the sheet itself is shared:
 * a fix to it reaches ObsiTube and ObsiRead at once.
 */
interface SaveSubject {
    val title: String
    /** The channel or the author. */
    val subtitle: String
    /** The link a note keeps, to find it in the vault again. */
    val sourceUrl: String
    /** What you filled in the last time you saved this one. */
    val lastClip: ClipMemo?

    fun build(
        template: ClipTemplate, settings: AppSettings, overrides: Map<String, String> = emptyMap(),
        name: String? = null, folder: String? = null, vault: String? = null,
    ): BuiltNote

    fun variables(settings: AppSettings): Map<String, String>

    /** The property values a note already has, by lower-case name. */
    fun readValues(text: String): Map<String, String>

    /** Adds what is new to a note that exists. [replace] lists properties whose new value wins. */
    fun merge(existing: String, note: BuiltNote, settings: AppSettings, replace: Set<String>): MergeResult

    fun saveMemo(memo: ClipMemo)

    /** The note now lives at [path] in [vault]. */
    fun savedTo(vault: String, path: String)

    @Composable
    fun Thumb(modifier: Modifier)
}
