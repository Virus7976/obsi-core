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
    // "3": the rules changed, so ideas made with the old rules are asked again.
    private fun prefs(c: Context) = c.getSharedPreferences("obsi_tag_ideas3", Context.MODE_PRIVATE)
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
     * The person's own tag for [tag], when they already have one for the same idea: "habit" gives
     * their "habits", and "carl-jung" gives their "jung". Null when none of theirs matches.
     */
    internal fun theirs(tag: String, mine: Collection<String>): String? {
        if (tag.isBlank()) return null
        fun same(a: String, b: String) = a == b || a == b + "s" || b == a + "s" || a == b + "es" || b == a + "es" ||
            a.replace("-", "") == b.replace("-", "")
        mine.firstOrNull { same(it, tag) }?.let { return it }
        val words = tag.split('-', '/').filter { it.length > 2 }
        return if (words.size > 1) mine.firstOrNull { m -> words.any { same(m, it) } } else null
    }

    /**
     * True when a new [tag] is a real topic: one word, and not filler. A tag the person already uses
     * is always kept, whatever its shape.
     */
    internal fun keep(tag: String, mine: Collection<String> = emptyList()): Boolean {
        if (tag.isBlank()) return false
        if (tag in mine) return true
        if (tag in filler) return false
        return !tag.contains('-') && !tag.contains('/')
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
                    "Start with their own tags: use every one of them that fits, copied exactly. " +
                    "Add a new tag only for a topic none of theirs covers. " +
                    "A tag is one word naming a topic: a theme, a field, or a person (jung, not carl-jung). " +
                    "Never copy a phrase from the text or the note, and never use empty tags like note, quote, idea or life. " +
                    "The note shows what the person cares about here; use it to pick the topics. " +
                    "Tags are lower case, one word, no \"#\".\n" +
                    "Example. Their tags: habits, stoicism, focus. Text: \"We are what we repeatedly do.\" Note: \"start small every day\". " +
                    "Good: habits, discipline, aristotle. Bad: habit, start-small, note.\n" +
                    "Reply with JSON only: {\"tags\": [\"...\"]}.",
                "Their tags: ${mine.joinToString(", ").ifBlank { "(none yet)" }}\n\n$body",
                fast = true, jsonOut = true, maxTokens = 150,
            ),
        )?.get("tags")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.let(::clean) }
            ?.map { theirs(it, mine) ?: it }?.filter { keep(it, mine) }?.distinct()?.take(6)
            ?: throw AiException("No tag ideas came back. Try again.")
        // Their own tags first, in the order they were given.
        val sorted = out.sortedBy { if (it in mine) 0 else 1 }
        prefs(context).edit().putString(key(text, note), sorted.joinToString(",")).apply()
        sorted
    }
}
