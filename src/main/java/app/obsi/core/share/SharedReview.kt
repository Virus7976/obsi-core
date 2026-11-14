package app.obsi.core.share

import android.content.Context
import android.net.Uri
import android.os.Bundle
import app.obsi.core.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One review across the three apps. Each app keeps its own schedule, lists what is due, and takes
 * "keep", "later" or "drop" for its highlights from the other apps. So any app can show every
 * due highlight, and none comes up twice.
 */
object SharedReview {
    /** This app's highlights that are due now. Set by the app. */
    @Volatile
    var due: (() -> List<SharedHighlight>)? = null

    /** Keep, skip or drop one of this app's highlights, by its key. Set by the app. */
    @Volatile
    var act: ((key: String, action: String) -> Unit)? = null

    /** The other apps' due highlights. */
    suspend fun fromOthers(context: Context): List<SharedHighlight> = withContext(Dispatchers.IO) {
        Brand.others.filter { it.installed(context) }.flatMap { SharedHighlights.read(context, it, "due") }
    }

    /** This app's own due highlights. */
    fun own(): List<SharedHighlight> = runCatching { due?.invoke() }.getOrNull().orEmpty()

    /** Sends [action] to the app that owns [h]. */
    suspend fun act(context: Context, h: SharedHighlight, action: String) = withContext(Dispatchers.IO) {
        if (h.app == Brand.app) { act?.invoke(h.key, action); return@withContext }
        runCatching {
            context.contentResolver.call(
                Uri.parse("content://${SharedHighlights.authority(h.app)}"), "review", h.key,
                Bundle().apply { putString("action", action) },
            )
        }
    }
}
