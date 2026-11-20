package app.obsi.core.data

import android.content.Context
import app.obsi.core.ai.Ai
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Flashcards from highlights: one key word or short phrase is hidden, you think of it, then show it.
 * The word is picked by plain rules, so it works with no AI. With an AI service set up, a better
 * pick is asked for quietly and kept.
 */
object Cloze {
    /** Where the hidden part is in the text. */
    data class Gap(val start: Int, val end: Int)

    private val stop = setOf(
        "about", "above", "after", "again", "against", "almost", "along", "already", "also", "although", "always", "among", "another",
        "anything", "around", "because", "become", "becomes", "been", "before", "being", "below", "between", "both", "cannot", "could",
        "doing", "during", "each", "either", "else", "enough", "even", "ever", "every", "everything", "first", "from", "further", "going",
        "have", "having", "here", "itself", "just", "know", "least", "less", "like", "little", "made", "make", "many", "maybe", "might",
        "more", "most", "much", "must", "myself", "never", "nothing", "often", "only", "other", "others", "ourselves", "over", "perhaps",
        "quite", "rather", "really", "same", "should", "since", "some", "something", "still", "such", "than", "that", "their", "them",
        "themselves", "then", "there", "these", "they", "thing", "things", "think", "this", "those", "though", "through", "thus", "together",
        "too", "toward", "towards", "under", "until", "upon", "very", "want", "well", "were", "what", "whatever", "when", "where", "whether",
        "which", "while", "whom", "whose", "will", "with", "within", "without", "would", "your", "yours", "yourself", "people", "actually",
        "going", "gonna", "kind", "lot", "right", "said", "says", "yeah", "okay", "sort", "basically", "literally", "into", "onto", "unto",
        "shall", "said", "hath", "thee", "thou", "thy", "unto", "lord",
    )

    /** The part to hide, or null when the text is too short to make a card of. */
    fun pick(text: String): Gap? {
        // A comma or dot between digits stays in the word: "10,000", "3.5".
        val words = Regex("""[\p{L}\p{N}](?:[\p{L}\p{N}'’-]|(?<=\d)[.,](?=\d))*""").findAll(text).toList()
        if (words.size < 5) return null
        data class Cand(val s: Int, val e: Int, val score: Double)
        val cands = ArrayList<Cand>()
        words.forEachIndexed { i, m ->
            val w = m.value.trimEnd('\'', '’', '-')
            val lower = w.lowercase()
            if (lower in stop || w.length < 4 && !w.any { it.isDigit() }) return@forEachIndexed
            val sentenceStart = i == 0 || text.substring(words[i - 1].range.last + 1, m.range.first).any { it in ".!?:\n" }
            var score = w.length.toDouble().coerceAtMost(12.0)
            if (w.any { it.isDigit() }) score += 9
            if (w.first().isUpperCase() && !sentenceStart) score += 8
            if (w.length >= 8) score += 3
            // Words near the middle make better cards than the first or last word.
            val mid = 1.0 - kotlin.math.abs(i - words.size / 2.0) / words.size
            score += mid * 2
            cands += Cand(m.range.first, m.range.first + w.length, score)
            // A name of two words: "Marcus Aurelius".
            val next = words.getOrNull(i + 1)
            if (next != null && w.first().isUpperCase() && next.value.first().isUpperCase() && !sentenceStart &&
                text.substring(m.range.last + 1, next.range.first).isBlank()
            ) cands += Cand(m.range.first, next.range.last + 1, score + 8)
        }
        val best = cands.maxByOrNull { it.score } ?: return null
        return Gap(best.s, best.e)
    }

    /** [phrase] in [text], when it is really there. */
    fun find(text: String, phrase: String): Gap? {
        val p = phrase.trim().trim('"', '\'', '.', ',')
        if (p.isBlank() || p.length > text.length / 2 + 1) return null
        val i = text.indexOf(p, ignoreCase = true)
        return if (i < 0) null else Gap(i, i + p.length)
    }

    // ---------- the quiet better pick ----------

    private fun prefs(c: Context) = c.getSharedPreferences("obsi_cloze", Context.MODE_PRIVATE)

    /** A pick made before, by the AI service. */
    fun saved(context: Context, id: String, text: String): Gap? = prefs(context).getString(id, null)?.let { find(text, it) }

    /**
     * Asks the AI service, once, for the best part to hide in each of these highlights, and keeps the answers.
     * Does nothing when no service is set up. Never throws.
     */
    suspend fun improve(context: Context, items: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        if (!Ai.store(context).value.ready) return@withContext
        val todo = items.filter { (id, text) -> prefs(context).getString(id, null) == null && pick(text) != null }.take(12)
        if (todo.isEmpty()) return@withContext
        runCatching {
            val list = todo.mapIndexed { i, (_, t) -> "${i + 1}. ${t.take(300)}" }.joinToString("\n")
            val o: JsonObject = Ai.jsonObject(
                Ai.ask(
                    context,
                    "You make fill-in-the-blank flashcards from a person's highlights. For each numbered text, pick the one word or short phrase " +
                        "(at most 3 words) that is the key idea worth remembering: a name, a number, a term, or the main concept. Never a common word. " +
                        "Copy it exactly as it appears in the text. Reply with JSON only: {\"1\": \"...\", \"2\": \"...\"}.",
                    list, fast = true, jsonOut = true, maxTokens = 800,
                ),
            ) ?: return@runCatching
            val e = prefs(context).edit()
            todo.forEachIndexed { i, (id, text) ->
                // A highlight is asked about once. With no good answer it keeps the plain pick, and is not sent again.
                val p = o[(i + 1).toString()]?.jsonPrimitive?.contentOrNull
                e.putString(id, if (p != null && find(text, p) != null) p else "")
            }
            e.apply()
        }
    }
}
