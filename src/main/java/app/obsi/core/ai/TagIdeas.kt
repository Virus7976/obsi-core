package app.obsi.core.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tag ideas for a highlight or a saved item, from the quote and the person's own note together.
 * A tag names a topic to find the quote by later: a theme, a field, a person, a work. It never
 * repeats a phrase from the quote or the note. Tags the person already uses come first, so the same
 * idea always gets the same tag. Asked once per quote and note, and kept on the phone.
 */
object TagIdeas {
    // "2": the rules changed, so ideas made with the old rules are asked again.
    private fun prefs(c: Context) = c.getSharedPreferences("obsi_tag_ideas2", Context.MODE_PRIVATE)
    private fun squash(s: String) = s.trim().replace(Regex("""\s+"""), " ")
    private fun key(text: String, note: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest((squash(text) + "\u0000" + squash(note)).lowercase().toByteArray())
        .joinToString("") { "%02x".format(it) }.take(32)

    fun cached(context: Context, text: String, note: String = ""): List<String>? =
        prefs(context).getString(key(text, note), null)?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }

    /** A tag as people type it in Obsidian: lower case, words joined by "-", no "#". */
    fun clean(t: String): String = t.trim().removePrefix("#").lowercase().replace(Regex("""[\s_]+"""), "-").replace(Regex("""[^\p{L}\p{N}/-]"""), "").trim('-')

    /** Tags that say nothing about the quote. */
    private val filler = setOf(
        "note", "notes", "quote", "quotes", "highlight", "highlights", "idea", "ideas", "thought", "thoughts",
        "insight", "insights", "wisdom", "life", "important", "interesting", "misc", "general", "video",
    )

    /**
     * True when [tag] is a real topic. False for filler, and for a phrase of three or more words
     * copied from the quote or the note: "live-your-own-life" from the note "live your own life".
     * A tag the person already uses is always kept.
     */
    internal fun keep(tag: String, text: String, note: String, mine: Collection<String> = emptyList()): Boolean {
        if (tag.isBlank()) return false
        if (tag in mine) return true
        if (tag in filler) return false
        val words = tag.split('-', '/').filter { it.isNotBlank() }
        if (words.size > 3) return false
        if (words.size >= 3) {
            val phrase = words.joinToString(" ")
            fun has(s: String) = " ${s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ")} ".contains(" $phrase ")
            if (has(text) || has(note)) return false
        }
        return true
    }

    suspend fun suggest(context: Context, text: String, known: List<String>, note: String = ""): List<String> = withContext(Dispatchers.IO) {
        cached(context, text, note)?.let { return@withContext it }
        val mine = known.map { clean(it) }.filter { it.isNotBlank() }.distinct().take(150)
        // Short on purpose: the quote and the note are cut, so a long passage costs the same as a short one.
        val body = buildString {
            append("Text: ").append(squash(text).take(if (note.isBlank()) 500 else 350))
            if (note.isNotBlank()) append("\n\nTheir note: ").append(squash(note).take(350))
        }
        val out = Ai.jsonObject(
            Ai.ask(
                context,
                "Suggest 3 to 6 tags that a person would search for to find this highlight again in a year. " +
                    "A tag is a topic: a theme, a field, a named person or work. Name what it is about, in your own words. " +
                    "Never copy a phrase from the text or the note into a tag, and never use empty tags like note, quote, idea or life. " +
                    "The note shows what the person cares about here; use it to pick the topics, not as a tag. " +
                    "When one of their own tags fits, use it, copied exactly; add new tags for anything theirs do not cover. " +
                    "Tags are lower case, 1 or 2 words joined by \"-\", no \"#\".\n" +
                    "Example. Text: \"We are what we repeatedly do.\" Note: \"start small every day\". " +
                    "Good: habits, discipline, aristotle. Bad: start-small-every-day, note.\n" +
                    "Reply with JSON only: {\"tags\": [\"...\"]}.",
                "Their tags: ${mine.joinToString(", ").ifBlank { "(none yet)" }}\n\n$body",
                fast = true, jsonOut = true, maxTokens = 150,
            ),
        )?.get("tags")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.let(::clean) }
            ?.filter { keep(it, text, note, mine) }?.distinct()?.take(6)
            ?: throw AiException("No tag ideas came back. Try again.")
        // Their own tags first, in the order they were given.
        val sorted = out.sortedBy { if (it in mine) 0 else 1 }
        prefs(context).edit().putString(key(text, note), sorted.joinToString(",")).apply()
        sorted
    }
}
