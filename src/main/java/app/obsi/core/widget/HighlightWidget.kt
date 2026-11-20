package app.obsi.core.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import app.obsi.core.Brand
import app.obsi.core.R
import app.obsi.core.data.Activity
import app.obsi.core.link.Links
import app.obsi.core.share.SharedHighlight
import app.obsi.core.share.SharedReview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The home-screen widget: one of your highlights each day from every Obsi app, your streak and
 * time today, and how many highlights wait for review. Tap the highlight to open it where it is.
 * No AI.
 */
class HighlightWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val done = goAsync()
        scope.launch {
            try { draw(context, manager, ids) } finally { done.finish() }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Draws every widget of this app again. Cheap when there are none. */
        suspend fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, HighlightWidget::class.java))
            if (ids.isNotEmpty()) draw(context, m, ids)
        }

        /** The highlight for [day]: the same one all day, a new one each day, short enough to read at a glance. */
        fun pick(all: List<SharedHighlight>, day: LocalDate): SharedHighlight? {
            val good = all.filter { it.text.length in 12..420 }.sortedBy { it.key.ifBlank { it.text } }
            val list = good.ifEmpty { all.filter { it.text.isNotBlank() } }
            if (list.isEmpty()) return null
            val i = Math.floorMod((day.toEpochDay() * 2654435761L).toInt(), list.size)
            return list[i]
        }

        private suspend fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val data = runCatching { Activity.raw(context) }.getOrNull()
            val stats = data?.let { runCatching { Activity.build(it.days, it.highlights) }.getOrNull() }
            val due = runCatching { SharedReview.own().size + SharedReview.fromOthers(context).size }.getOrDefault(0)
            val today = LocalDate.now()
            val h = data?.let { pick(it.highlights, today) }
            val todaySec = stats?.week?.lastOrNull()?.second?.values?.sum() ?: 0

            val v = RemoteViews(context.packageName, R.layout.obsi_widget)
            val top = buildList {
                add(if (todaySec >= 60) "Today ${Activity.duration(todaySec)}" else "Today")
                stats?.streak?.takeIf { it > 1 }?.let { add("$it-day streak") }
            }.joinToString("  ·  ")
            v.setTextViewText(R.id.obsi_widget_top, top)
            if (h != null) {
                v.setTextViewText(R.id.obsi_widget_quote, "“" + h.text.trim().replace(Regex("""\s+"""), " ") + "”")
                v.setTextViewText(R.id.obsi_widget_source, listOf(h.itemTitle, h.place, h.app.title).filter { it.isNotBlank() }.distinct().joinToString("  ·  "))
                v.setViewVisibility(R.id.obsi_widget_source, View.VISIBLE)
                open(context, h)?.let { v.setOnClickPendingIntent(R.id.obsi_widget_quote_box, it) }
            } else {
                v.setTextViewText(R.id.obsi_widget_quote, "Your highlights show here, one each day.")
                v.setViewVisibility(R.id.obsi_widget_source, View.GONE)
                home(context, review = false)?.let { v.setOnClickPendingIntent(R.id.obsi_widget_quote_box, it) }
            }
            if (due > 0) {
                v.setTextViewText(R.id.obsi_widget_review, if (due == 1) "1 to review" else "$due to review")
                v.setViewVisibility(R.id.obsi_widget_review, View.VISIBLE)
                home(context, review = true)?.let { v.setOnClickPendingIntent(R.id.obsi_widget_review, it) }
            } else v.setViewVisibility(R.id.obsi_widget_review, View.GONE)
            home(context, review = false)?.let { v.setOnClickPendingIntent(R.id.obsi_widget_root, it) }
            manager.updateAppWidget(ids, v)
        }

        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun home(context: Context, review: Boolean): PendingIntent? {
            val i = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (review) i.putExtra("review", true)
            return PendingIntent.getActivity(context, if (review) 2 else 1, i, FLAGS)
        }

        /** Opens the highlight in its own app, at its place. */
        private fun open(context: Context, h: SharedHighlight): PendingIntent? {
            val i = context.packageManager.getLaunchIntentForPackage(h.app.pkg) ?: return home(context, false)
            i.putExtra(Links.EXTRA_OPEN_ID, h.itemId).putExtra(Links.EXTRA_OPEN_AT, h.at).putExtra(Links.EXTRA_FROM, Brand.app.pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(context, 3, i, FLAGS)
        }
    }
}
