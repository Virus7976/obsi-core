package app.obsi.core.ai

import android.content.Context
import app.obsi.core.share.SharedHit
import app.obsi.core.share.SharedSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * An answer to a question, written only from what you saved in the Obsi apps: highlights, notes,
 * transcripts and titles. [text] cites its sources as [1], [2]; [sources] are those, in order.
 */
data class Answer(val question: String, val text: String, val sources: List<SharedHit>)

object Answers {
    /** Words that make a search look like a question, not a name. */
    private val starts = setOf(
        "what", "whats", "what's", "how", "why", "who", "when", "where", "which", "does", "do", "did", "is", "are",
        "can", "should", "could", "would", "was", "were", "explain", "summarize", "summarise", "compare", "tell", "list",
    )

    /** "how does sleep affect memory" is a question; "huberman sleep" is a search. */
    fun looksLikeQuestion(q: String): Boolean {
        val t = q.trim()
        val words = t.split(' ').filter { it.isNotBlank() }
        if (words.size < 3) return false
        return t.endsWith("?") || words.first().lowercase().trimEnd(',', ':') in starts
    }

    private val cache = HashMap<String, Answer>()
    fun cached(q: String): Answer? = cache[q.trim().lowercase()]
    fun forget(q: String) { cache.remove(q.trim().lowercase()) }

    suspend fun ask(context: Context, question: String): Answer = withContext(Dispatchers.IO) {
        val q = question.trim()
        cached(q)?.let { return@withContext it }

        // 1. A few short searches that would find the answer. The words of the question are one of them.
        val searches = runCatching {
            val o = Ai.jsonObject(
                Ai.ask(
                    context,
                    "You turn a question into short keyword searches for a personal library of highlights, notes, video and podcast transcripts, articles and Bible passages. " +
                        "Reply with JSON only: {\"searches\": [\"...\"]}. Give 3 to 5 searches of 1 to 3 words each, the words most likely to appear in the text that answers it. No quotes, no operators.",
                    q, fast = true, jsonOut = true, maxTokens = 2000,
                ),
            )
            o?.get("searches")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }?.filter { it.isNotBlank() }.orEmpty()
        }.getOrDefault(emptyList())
        val plain = q.lowercase().split(' ', '?', ',', '.').filter { it.length > 3 && it !in stop }.take(4)
        val all = (searches + plain).distinct().take(8)

        // 2. Every Obsi app searches at the same time.
        val hits = coroutineScope {
            all.map { s -> async { SharedSearch.own(s) + SharedSearch.fromOthers(context, s) } }.awaitAll()
        }.flatten()
        val picked = hits
            .filter { it.snippet.isNotBlank() || it.kind == "item" }
            .groupBy { Triple(it.app, it.itemId, it.snippet.take(60)) }
            .map { (_, same) -> same.maxBy { it.score }.let { best -> best.copy(score = best.score + (same.size - 1) * 25) } }
            .sortedByDescending { it.score + if (it.kind == "highlight") 20 else 0 }
            .take(24)
        if (picked.isEmpty()) return@withContext Answer(q, "Nothing you saved talks about this yet.", emptyList())

        // 3. The answer, from those sources only.
        val sources = picked.mapIndexed { i, h ->
            val where = listOf(h.app.title, h.title, h.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
            "[${i + 1}] $where\n${h.snippet.ifBlank { "(title only)" }}"
        }.joinToString("\n\n")
        val text = Ai.ask(
            context,
            "You answer a person's question using only the numbered sources from their own library. " +
                "Write in plain, short sentences. Use Markdown: a short direct answer first, then bullet points or a few short paragraphs if needed, at most about 180 words. " +
                "After each claim, cite the sources it comes from like [2] or [1, 4]. Never cite a number that is not in the list. " +
                "If the sources do not answer the question, say so in one sentence and say what they do cover. Do not add knowledge from outside the sources. " +
                "Do not mention that you are an AI, and do not repeat the question.",
            "Question: $q\n\nSources:\n$sources",
        )
        // Only the sources the answer uses, numbered again from 1.
        val used = Regex("""\[(\d+(?:\s*,\s*\d+)*)]""").findAll(text)
            .flatMap { it.groupValues[1].split(',').mapNotNull { n -> n.trim().toIntOrNull() } }
            .filter { it in 1..picked.size }.distinct().toList()
        val renumber = used.withIndex().associate { (i, n) -> n to i + 1 }
        val clean = Regex("""\[(\d+(?:\s*,\s*\d+)*)]""").replace(text) { m ->
            val nums = m.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull()?.let(renumber::get) }.distinct()
            if (nums.isEmpty()) "" else "[" + nums.joinToString(", ") + "]"
        }
        Answer(q, clean.trim(), used.map { picked[it - 1] }).also { cache[q.lowercase()] = it }
    }

    private val stop = setOf("what", "does", "about", "with", "from", "that", "this", "have", "there", "their", "they", "which", "would", "should", "could", "when", "where", "your", "into", "said", "says")
}
