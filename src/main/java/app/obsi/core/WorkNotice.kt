package app.obsi.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * One quiet notification for work that runs in the background: saving audio, downloading an update.
 * It shows the progress, then says it is done or what went wrong, and goes away by itself.
 * Each job has its own [tag], so two jobs show as two notifications.
 */
object WorkNotice {
    private const val CHANNEL = "work"
    private const val ID = 7400
    private val lastPost = HashMap<String, Long>()

    private fun manager(ctx: Context): NotificationManager? {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return null
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Background work", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of saving audio and downloading updates"
                setShowBadge(false)
            })
        }
        return nm
    }

    private fun builder(ctx: Context, title: String, text: String) =
        NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                    setContentIntent(PendingIntent.getActivity(ctx, ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }

    /** Shows how far a job is. [total] 0 shows a bar that only moves. At most a few posts a second. */
    fun progress(ctx: Context, tag: String, title: String, text: String, done: Int, total: Int) {
        val now = SystemClock.uptimeMillis()
        synchronized(lastPost) {
            if (done in 1 until total && now - (lastPost[tag] ?: 0L) < 400) return
            lastPost[tag] = now
        }
        val nm = manager(ctx) ?: return
        val n = builder(ctx, title, text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(total, done.coerceAtMost(total), total <= 0)
            .build()
        nm.notify(tag, ID, n)
    }

    /** The job is done. The notice stays a few seconds, then goes. */
    fun done(ctx: Context, tag: String, title: String, text: String) = end(ctx, tag, title, text, android.R.drawable.stat_sys_download_done, 6_000)

    /** The job stopped. The notice says why and stays until it is swiped away. */
    fun failed(ctx: Context, tag: String, title: String, text: String) = end(ctx, tag, title, text, android.R.drawable.stat_notify_error, 0)

    private fun end(ctx: Context, tag: String, title: String, text: String, icon: Int, timeout: Long) {
        synchronized(lastPost) { lastPost.remove(tag) }
        val nm = manager(ctx) ?: return
        val b = builder(ctx, title, text).setSmallIcon(icon).setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        if (timeout > 0) b.setTimeoutAfter(timeout)
        nm.notify(tag, ID, b.build())
    }

    /** Takes the notice away, for a job that was stopped on purpose. */
    fun cancel(ctx: Context, tag: String) {
        synchronized(lastPost) { lastPost.remove(tag) }
        ctx.getSystemService(NotificationManager::class.java)?.cancel(tag, ID)
    }
}
