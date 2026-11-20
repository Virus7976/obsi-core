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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Search by meaning: finds your own highlights, passages and notes that match an idea, even when
 * the words differ. The AI service never writes text for you. It only does two small jobs: it
 * suggests other words for the same idea, and it picks which of the phone's results really match,
 * by number. You always see your own words, as you saved them.
 *
 * Token use is kept low: the phone does every search itself, the service sees only short pieces of
 * the results, it answers with numbers, and both answers are kept on the phone so a search is never
 * sent twice.
 */
object Meaning {
    /** What a meaning search found, best first. Empty when nothing fits the idea. */
    data class Found(val query: String, val hits: List<SharedHit>)

    /** Offered for a search of three letters or more, when an AI service is set up. */
    fun offered(query: String): Boolean = query.trim().length >= 3 && Ai.readyNow

    private val cache = HashMap<String, Found>()
    fun cached(q: String): Found? = cache[q.trim().lowercase()]
    fun forget(q: String) { cache.remove(q.trim().lowercase()) }

    private fun prefs(c: Context, name: String) = c.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    private const val SEP = "\u001f"

    private fun trim(p: android.content.SharedPreferences) {
        if (p.all.size > 300) p.edit().apply { p.all.keys.take(100).forEach { remove(it) } }.apply()
    }

    suspend fun find(context: Context, query: String): Found = withContext(Dispatchers.IO) {
        val q = query.trim()
        val key = q.lowercase()
        cached(q)?.let { return@withContext it }

        // 1. Other words for the same idea. Asked once per search, then kept.
        val wordsStore = prefs(context, "obsi_meaning_words")
        val related = wordsStore.getString(key, null)?.split(SEP)?.filter { it.isNotBlank() } ?: runCatching {
            val o = Ai.jsonObject(
                Ai.ask(
                    context,
                    "Give other words for what this search means, as they might be written in a person's highlights, notes, transcripts, articles or Bible passages. " +
                        "Synonyms and closely related ideas, 1 to 3 words each, 6 to 10 of them. Not the search's own words. Reply with JSON only: {\"words\": [\"...\"]}.",
                    q, fast = true, jsonOut = true, maxTokens = 300,
                ),
            )
            o?.get("words")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.lowercase() }?.filter { it.isNotBlank() }.orEmpty()
        }.getOrDefault(emptyList()).also { w ->
            if (w.isNotEmpty()) { trim(wordsStore); wordsStore.edit().putString(key, w.joinToString(SEP)).apply() }
        }

        // 2. The phone searches every Obsi app with the search's own words and the related ones. Free.
        val own = key.split(' ', '?', ',', '.', '!', ';', ':').filter { it.length > 2 && it !in stop }
        val terms = (listOf(key).filter { ' ' in it || it.length > 2 } + own + related).distinct().take(14)
        val hits = coroutineScope {
            terms.map { s -> async { SharedSearch.own(s) + SharedSearch.fromOthers(context, s) } }.awaitAll()
        }.flatten()
        val pool = hits
            .filter { it.snippet.isNotBlank() || it.kind == "item" }
            .groupBy { Triple(it.app, it.itemId, it.snippet.take(60)) }
            .map { (_, same) -> same.maxBy { it.score }.let { b -> b.copy(score = b.score + (same.size - 1) * 25) } }
            .sortedByDescending { it.score + if (it.kind == "highlight") 20 else 0 }
            .take(36)
        if (pool.size <= 1) return@withContext Found(q, pool).also { cache[key] = it }

        // 3. The service picks which results match the idea, by number. Kept per search and results.
        val list = pool.mapIndexed { i, h ->
            val text = h.snippet.ifBlank { h.title }.replace(Regex("""\s+"""), " ").take(180)
            "${i + 1}. $text" + if (h.snippet.isNotBlank()) " (${h.title.take(60)})" else ""
        }.joinToString("\n")
        val fp = sha(key + "\u0000" + list)
        val idStore = prefs(context, "obsi_meaning_picks")
        val ids = idStore.getString(fp, null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: runCatching {
            val o = Ai.jsonObject(
                Ai.ask(
                    context,
                    "A person searches their own library. Pick the numbered items that match what the search means, even when the words differ. " +
                        "Best match first, at most 12. Leave out items that do not fit. Reply with JSON only: {\"ids\": [3, 1]}.",
                    "Search: $q\n\nItems:\n$list",
                    fast = true, jsonOut = true, maxTokens = 200,
                ),
            ) ?: return@runCatching null
            o["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty()
        }.getOrNull()?.also { trim(idStore); idStore.edit().putString(fp, it.joinToString(",")).apply() }

        // With no answer from the service, the phone's own order stands.
        val picked = ids?.filter { it in 1..pool.size }?.distinct()?.map { pool[it - 1] } ?: pool.take(12)
        Found(q, picked).also { cache[key] = it }
    }

    private val stop = setOf(
        "what", "does", "about", "with", "from", "that", "this", "have", "there", "their", "they", "which", "would", "should",
        "could", "when", "where", "your", "into", "said", "says", "how", "why", "who", "the", "and", "for", "are", "was", "were",
    )
}
