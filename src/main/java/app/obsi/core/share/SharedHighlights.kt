package app.obsi.core.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.obsi.core.Brand
import app.obsi.core.ObsiApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One highlight, in the form every Obsi app can read. */
data class SharedHighlight(
    val app: ObsiApp,
    /** The video, article or passage it is in. */
    val itemId: String,
    val itemTitle: String,
    val text: String,
    val note: String,
    /** The marker colour, as 0xAARRGGBB. */
    val color: Long,
    /** Where it is: seconds, a word number or a verse key. The owning app reads it back. */
    val at: String,
    /** Where it is, for people: "12:03", "John 3:16". */
    val place: String,
    val created: Long,
    val tags: List<String> = emptyList(),
    /** The highlight's own id, for review. */
    val key: String = "",
)

/**
 * Highlights shared between the Obsi apps. Each app tells [source] how to list its own. The other
 * apps read them through [SharedHighlightsProvider]. Only an app signed with one of the three Obsi
 * keys may read them (see the permission in the core manifest).
 */
object SharedHighlights {
    /** Lists this app's highlights. Set by the app in Application.onCreate. */
    @Volatile
    var source: (() -> List<SharedHighlight>)? = null

    private val columns = arrayOf("item_id", "item_title", "text", "note", "color", "at", "place", "created", "tags", "key")

    fun authority(app: ObsiApp) = "${app.pkg}.obsi"

    internal fun cursor(rows: List<SharedHighlight>): Cursor = MatrixCursor(columns, rows.size).apply {
        rows.forEach { addRow(arrayOf<Any>(it.itemId, it.itemTitle, it.text, it.note, it.color, it.at, it.place, it.created, it.tags.joinToString("\u001f"), it.key)) }
    }

    /** The highlights of the other Obsi apps on this phone. An app that is missing or old gives none. */
    suspend fun fromOthers(context: Context): List<SharedHighlight> = withContext(Dispatchers.IO) {
        Brand.others.filter { it.installed(context) }.flatMap { other -> read(context, other) }
    }

    fun read(context: Context, from: ObsiApp, path: String = "highlights"): List<SharedHighlight> = runCatching {
        val uri = Uri.parse("content://${authority(from)}/$path")
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val out = ArrayList<SharedHighlight>(c.count)
            fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name)).orEmpty()
            while (c.moveToNext()) {
                out += SharedHighlight(
                    app = from,
                    itemId = s("item_id"),
                    itemTitle = s("item_title"),
                    text = s("text"),
                    note = s("note"),
                    color = c.getLong(c.getColumnIndexOrThrow("color")),
                    at = s("at"),
                    place = s("place"),
                    created = c.getLong(c.getColumnIndexOrThrow("created")),
                    tags = s("tags").split('\u001f').filter { it.isNotBlank() },
                    key = if (c.getColumnIndex("key") >= 0) s("key") else "",
                )
            }
            out
        }.orEmpty()
    }.getOrDefault(emptyList())
}

/** Hands this app's highlights to the other Obsi apps. Read only. */
class SharedHighlightsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.lastPathSegment == "setup") return SharedSetup.cursor()
        if (uri.lastPathSegment == "ai") return context?.let { app.obsi.core.ai.Ai.cursor(it) }
        if (uri.lastPathSegment == "weekly") return context?.let { app.obsi.core.obsidian.WeeklyNote.cursor(it) }
        if (uri.lastPathSegment == "model") return context?.let { app.obsi.core.ai.OnPhone.modelCursor(it) }
        if (uri.lastPathSegment == "style") return SharedStyle.cursor()
        if (uri.lastPathSegment == "activity") return context?.let { app.obsi.core.data.SharedActivity.cursor(it) }
        if (uri.lastPathSegment == "search") return SharedSearch.cursor(selectionArgs?.firstOrNull().orEmpty())
        if (uri.lastPathSegment == "due")return SharedHighlights.cursor(runCatching { SharedReview.due?.invoke() }.getOrNull().orEmpty())
        if (uri.lastPathSegment != "highlights") return null
        val rows = runCatching { SharedHighlights.source?.invoke() }.getOrNull().orEmpty()
        return SharedHighlights.cursor(rows)
    }

    /** "review": another Obsi app kept, skipped or dropped one of our highlights. */
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        val ctx = context ?: return null
        // call() has no permission of its own, so check the same one the reads need.
        ctx.enforceCallingPermission(ctx.packageName + ".permission.OBSI_SHARE", "Only the Obsi apps may do this.")
        if (method == "review" && arg != null) SharedReview.act?.invoke(arg, extras?.getString("action") ?: "later")
        return null
    }

    /** "backup" and "restore": another Obsi app saves or puts back this app's part of a backup. */
    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        val ctx = context ?: return null
        // A file is not covered by the read permission alone, so check it here, for reading and writing.
        ctx.enforceCallingPermission(ctx.packageName + ".permission.OBSI_SHARE", "Only the Obsi apps may do this.")
        // "model": the AI model file this app keeps, lent to the other apps to read, so it is kept once.
        if (uri.lastPathSegment == "model") return app.obsi.core.ai.OnPhone.ownFile(ctx)?.let {
            android.os.ParcelFileDescriptor.open(it, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        return SharedBackup.open(ctx, uri.lastPathSegment.orEmpty(), mode)
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.obsi.highlight"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
