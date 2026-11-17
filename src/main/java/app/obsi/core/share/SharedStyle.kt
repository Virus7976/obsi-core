package app.obsi.core.share

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.obsi.core.Brand
import app.obsi.core.data.AppSettings
import app.obsi.core.data.CustomPalette
import app.obsi.core.data.SettingsStore
import app.obsi.core.data.storeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** The look, as every Obsi app shares it: palette, light or dark, accent, motion and reading font. */
@Serializable
data class ObsiStyle(
    /** When it was last changed by hand, in any app. The newest one wins. */
    val at: Long = 0L,
    val themePreset: String = "obsidian",
    val customPalettes: List<CustomPalette> = emptyList(),
    val appearance: String = "auto",
    val accentOverride: Long = 0L,
    val motion: String = "lively",
    val roundness: Float = 1f,
    val readerFont: String = "serif",
    val readerTheme: String = "app",
    val fontWeight: Int = 400,
    /** False for an app with a look of its own (Obsi Bible). It shares only light, dark or black. */
    val full: Boolean = true,
) {
    /** The same look, whenever it was set. */
    fun sameLook(o: ObsiStyle) =
        if (!full || !o.full) appearance == o.appearance else copy(at = 0L) == o.copy(at = 0L)

    fun applyTo(s: AppSettings) = if (!full) s.copy(appearance = appearance, styleAt = at) else s.copy(
        themePreset = themePreset, customPalettes = customPalettes, appearance = appearance, accentOverride = accentOverride,
        motion = motion, roundness = roundness, readerFont = readerFont, readerTheme = readerTheme, fontWeight = fontWeight,
        styleAt = at,
    )

    companion object {
        fun of(s: AppSettings) = ObsiStyle(
            s.styleAt, s.themePreset, s.customPalettes, s.appearance, s.accentOverride, s.motion, s.roundness,
            s.readerFont, s.readerTheme, s.fontWeight,
        )
    }
}

/**
 * Change the look in one Obsi app and the others follow. Each app hands its look to the others through
 * the same guarded door as the highlights. When an app comes to the front, it takes the newest look.
 */
object SharedStyle {
    /** This app's look, or null for an app with its own fixed look. Set by the app. */
    @Volatile
    var source: (() -> ObsiStyle?)? = null

    internal fun cursor(): Cursor = MatrixCursor(arrayOf("json"), 1).apply {
        runCatching { source?.invoke() }.getOrNull()?.let { addRow(arrayOf<Any>(storeJson.encodeToString(ObsiStyle.serializer(), it))) }
    }

    fun read(context: Context, from: app.obsi.core.ObsiApp): ObsiStyle? = runCatching {
        context.contentResolver.query(Uri.parse("content://${SharedHighlights.authority(from)}/style"), null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else storeJson.decodeFromString(ObsiStyle.serializer(), c.getString(0))
        }
    }.getOrNull()

    /** The looks the other installed Obsi apps share. */
    suspend fun others(context: Context): List<ObsiStyle> = withContext(Dispatchers.IO) {
        Brand.others.filter { it.installed(context) }.mapNotNull { read(context, it) }
    }

    /** Takes the newest look from the other apps, if it is newer than this app's. True when it changed. */
    suspend fun pull(context: Context, settings: SettingsStore): Boolean = withContext(Dispatchers.IO) {
        val all = others(context)
        var changed = false
        // A whole look first, then a newer light-or-dark change from an app with its own look.
        for (newest in listOfNotNull(all.filter { it.full }.maxByOrNull { it.at }, all.filter { !it.full }.maxByOrNull { it.at })) {
            val mine = ObsiStyle.of(settings.value)
            if (newest.at <= mine.at) continue
            if (newest.sameLook(mine)) { settings.syncStyle { it.copy(styleAt = newest.at) }; continue }
            settings.syncStyle { newest.applyTo(it) }
            changed = true
        }
        changed
    }
}
