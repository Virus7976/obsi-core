package app.obsi.core.share

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.obsi.core.Brand
import app.obsi.core.ObsiApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** What one search found in an Obsi app, in a form every Obsi app can read and open. */
data class SharedHit(
    val app: ObsiApp,
    /** "item" (a video, article, lesson or chapter), "highlight", or "text" (words inside an item). */
    val kind: String,
    val title: String,
    val subtitle: String,
    /** The words that matched, for highlights and text. */
    val snippet: String,
    val itemId: String,
    /** Where in the item. The owning app reads it back, as with [SharedHighlight.at]. */
    val at: String,
    val score: Double,
    /** A highlight's colour, as 0xAARRGGBB. 0 for the rest. */
    val color: Long = 0,
)

/**
 * One search across the Obsi apps. Each app answers searches from the others through its share door.
 * An app that sets no [source] still answers, with its highlights.
 */
object SharedSearch {
    /** Searches this app. Set by the app in Application.onCreate. */
    @Volatile
    var source: ((String) -> List<SharedHit>)? = null

    private val columns = arrayOf("kind", "title", "subtitle", "snippet", "item_id", "at", "score", "color")

    /** This app's answer: its own search, or its highlights when it has none. */
    fun own(query: String): List<SharedHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        source?.let { s -> return runCatching { s(q) }.getOrDefault(emptyList()) }
        return highlights(q, runCatching { SharedHighlights.source?.invoke() }.getOrNull().orEmpty().map { it.copy(app = Brand.app) })
    }

    /** Highlights whose words, note or tags have every word of [query]. */
    fun highlights(query: String, all: List<SharedHighlight>, limit: Int = 30): List<SharedHit> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return all.mapNotNull { h ->
            val hay = (h.text + " " + h.note + " " + h.tags.joinToString(" ") + " " + h.itemTitle).lowercase()
            if (!words.all { it in hay }) return@mapNotNull null
            SharedHit(h.app, "highlight", h.itemTitle, h.place, h.text.ifBlank { h.note }.take(200), h.itemId, h.at, 50.0 + (if (h.text.lowercase().contains(query.lowercase())) 30 else 0), h.color)
        }.sortedByDescending { it.score }.take(limit)
    }

    internal fun cursor(query: String): Cursor = MatrixCursor(columns).apply {
        own(query).take(60).forEach { addRow(arrayOf<Any>(it.kind, it.title, it.subtitle, it.snippet, it.itemId, it.at, it.score, it.color)) }
    }

    fun read(context: Context, from: ObsiApp, query: String): List<SharedHit> = runCatching {
        val uri = Uri.parse("content://${SharedHighlights.authority(from)}/search")
        context.contentResolver.query(uri, null, null, arrayOf(query), null)?.use { c ->
            val out = ArrayList<SharedHit>(c.count)
            fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n)).orEmpty()
            while (c.moveToNext()) out += SharedHit(
                from, s("kind"), s("title"), s("subtitle"), s("snippet"), s("item_id"), s("at"),
                c.getDouble(c.getColumnIndexOrThrow("score")), c.getLong(c.getColumnIndexOrThrow("color")),
            )
            out
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** What the other Obsi apps on this phone found, best first. The apps are asked at the same time. */
    suspend fun fromOthers(context: Context, query: String): List<SharedHit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        coroutineScope {
            Brand.others.filter { it.installed(context) }.map { a -> async { read(context, a, query.trim()) } }.awaitAll().flatten()
        }.sortedByDescending { it.score }
    }
}
