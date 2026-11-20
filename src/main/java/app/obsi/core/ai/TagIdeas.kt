package app.obsi.core.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tag ideas for a highlight or a saved item. Tags you already use come first, so the same idea
 * always gets the same tag, and search finds it later with no AI at all. Asked only when you tap,
 * once per text, and kept on the phone.
 */
object TagIdeas {
    private fun prefs(c: Context) = c.getSharedPreferences("obsi_tag_ideas", Context.MODE_PRIVATE)
    private fun key(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.trim().lowercase().toByteArray())
        .joinToString("") { "%02x".format(it) }.take(32)

    fun cached(context: Context, text: String): List<String>? =
        prefs(context).getString(key(text), null)?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }

    /** A tag as people type it in Obsidian: lower case, words joined by "-", no "#". */
    fun clean(t: String): String = t.trim().removePrefix("#").lowercase().replace(Regex("""[\s_]+"""), "-").replace(Regex("""[^\p{L}\p{N}/-]"""), "").trim('-')

    suspend fun suggest(context: Context, text: String, known: List<String>): List<String> = withContext(Dispatchers.IO) {
        cached(context, text)?.let { return@withContext it }
        val mine = known.map { clean(it) }.filter { it.isNotBlank() }.distinct().take(150)
        val out = Ai.jsonObject(
            Ai.ask(
                context,
                "Suggest up to 5 tags for a person's highlight or saved item. Use their own tags when one fits, copied exactly. " +
                    "Add at most 2 new tags, only when none of theirs fit. Tags are lower case, 1 or 2 words joined by \"-\", no \"#\". " +
                    "Reply with JSON only: {\"tags\": [\"...\"]}.",
                "Their tags: ${mine.joinToString(", ").ifBlank { "(none yet)" }}\n\nText: ${text.trim().replace(Regex("""\s+"""), " ").take(500)}",
                fast = true, jsonOut = true, maxTokens = 150,
            ),
        )?.get("tags")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.let(::clean) }?.filter { it.isNotBlank() }?.distinct()?.take(5)
            ?: throw AiException("No tag ideas came back. Try again.")
        // Their own tags first, in the order they were given.
        val sorted = out.sortedBy { if (it in mine) 0 else 1 }
        prefs(context).edit().putString(key(text), sorted.joinToString(",")).apply()
        sorted
    }
}
