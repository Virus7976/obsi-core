package app.obsi.core.data

import kotlinx.serialization.Serializable

/**
 * One raw caption cue straight from YouTube.
 * [offs] holds the start of each spoken word, in seconds from [start]. Auto captions have it;
 * hand-made captions do not, and then the words are spread over the cue by their length.
 */
@Serializable
data class Cue(val start: Double, val dur: Double, val text: String, val offs: List<Double>? = null)

/** A readable line: several cues glued together so a sentence reads as one block. */
data class Line(val start: Double, val end: Double, val text: String)

@Serializable
data class Chapter(val start: Double, val title: String)

@Serializable
data class TranscriptData(
    val lang: String = "",
    val auto: Boolean = false,
    val cues: List<Cue> = emptyList(),
)

/**
 * A highlight is stored by time range, not by line number, so changing the
 * line length setting never breaks existing highlights.
 */
@Serializable
data class Highlight(
    val id: String,
    val startSec: Double,
    val endSec: Double,
    val text: String,
    val note: String = "",
    /** Index into [HighlightColors]. */
    val color: Int = 0,
    /** First and last word, by position in the transcript. -1 on highlights saved by version 1.0, which only know the time. */
    val w0: Int = -1,
    val w1: Int = -1,
    /** When it was made, in milliseconds. 0 on highlights from before review mode. */
    val createdAt: Long = 0L,
    /** Tags for this one highlight, without the #. They go into the note as #tags. */
    val tags: List<String> = emptyList(),
)

/**
 * The marker colours. In the note a colour is a coloured circle right after the opening marks.
 * New colours go at the end, because saved highlights remember a colour by its place in this list.
 */
data class HighlightColor(val name: String, val ui: Long, val emoji: String)

val HighlightColors = listOf(
    HighlightColor("Yellow", 0xFFF2C14E, "🟡"),
    HighlightColor("Red", 0xFFFF6B6B, "🔴"),
    HighlightColor("Green", 0xFF5BD98C, "🟢"),
    HighlightColor("Blue", 0xFF6AA8FF, "🔵"),
    HighlightColor("Purple", 0xFFB58CFF, "🟣"),
    HighlightColor("Orange", 0xFFF0A04B, "🟠"),
)

@Serializable
data class ClipMemo(
    val templateId: String,
    val name: String = "",
    val folder: String = "",
    val vault: String = "",
    val values: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class Playlist(
    val id: String,
    val title: String,
    val listId: String = "",
    val videoIds: List<String> = emptyList(),
    val importedAt: Long = 0L,
    /** When a video in this playlist is watched to the end, it leaves the playlist. */
    val removeWhenWatched: Boolean = false,
)

@Serializable
data class TemplateProperty(
    val id: String,
    val name: String,
    val value: String = "",
    val type: String = "",
    /** A box for it opens before the note saves. The value is what the box starts with. */
    val ask: Boolean = false,
)

@Serializable
data class ClipTemplate(
    val id: String,
    val name: String,
    val behavior: String = "create",
    val noteNameFormat: String = "{{title}}",
    val path: String = "Clippings",
    val noteContentFormat: String = "{{content}}",
    val vault: String = "",
    val properties: List<TemplateProperty> = emptyList(),
    /** The vault note this came from. Picking that note again updates this template. */
    val source: String = "",
    /** The note is a Templater template. Its script is kept, so only plain lines are ever written back. */
    val templater: Boolean = false,
)

/** A palette made in the palette maker. */
@Serializable
data class CustomPalette(val id: String, val name: String, val seed: Long, val style: String = "tonal")
