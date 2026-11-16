package app.obsi.core.link

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.obsi.core.Brand
import app.obsi.core.ObsiApp

/**
 * Sends a shared link to the app that reads it. A YouTube link goes to ObsiTube, a Bible link to
 * Obsi Bible, any other web page to ObsiRead. So it does not matter which app you pick in the
 * share menu.
 */
object Links {
    /** Put on a link that one Obsi app hands to another, so it is never sent back. */
    const val EXTRA_FROM = "app.obsi.FROM"

    /** Opens one saved thing in a sibling app: its id, and a place in it (seconds, word or verse). */
    const val EXTRA_OPEN_ID = "app.obsi.OPEN_ID"
    const val EXTRA_OPEN_AT = "app.obsi.OPEN_AT"

    private val youTubeHosts = setOf("youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "youtube-nocookie.com")
    private val bibleHosts = setOf(
        "bible.com", "biblegateway.com", "esv.org", "biblehub.com", "blueletterbible.org",
        "openbible.info", "biblia.com", "stepbible.org", "netbible.org",
    )

    private val videoFile = Regex("""\.(mp4|m4v|mkv|webm|mov|m3u8|mpd|mp3|m4a)$""")

    private val url = Regex("""(?:https?|obsibible)://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** The first link in a shared text, or null. */
    fun firstLink(text: String): String? = url.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?')

    /** Which app reads [text]. Null when it has no link: the app that got it keeps it. */
    fun target(text: String): ObsiApp? {
        val link = firstLink(text) ?: return null
        if (link.startsWith("obsibible:", true)) return ObsiApp.Bible
        val host = runCatching { Uri.parse(link).host.orEmpty() }.getOrDefault("").lowercase().removePrefix("www.")
        if (host.isBlank()) return null
        // A link straight to a video file is a lesson.
        val path = runCatching { Uri.parse(link).path.orEmpty() }.getOrDefault("").lowercase()
        if (videoFile.containsMatchIn(path)) return ObsiApp.Learn
        return when {
            youTubeHosts.any { host == it || host.endsWith(".$it") } -> ObsiApp.Tube
            bibleHosts.any { host == it || host.endsWith(".$it") } -> ObsiApp.Bible
            else -> ObsiApp.Read
        }
    }

    /**
     * Call this with every shared or opened text. Returns true when the text went to another app,
     * and this app should do nothing more with it.
     */
    fun route(context: Context, intent: Intent?, text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        // A link one Obsi app sent here stays here, even if it looks like it belongs elsewhere.
        if (intent?.getStringExtra(EXTRA_FROM) != null) return false
        val to = target(text) ?: return false
        if (to == Brand.app) return false
        // A video file goes to ObsiLearn only when it is on the phone. Otherwise the app that got it keeps it.
        if (to == ObsiApp.Learn && !to.installed(context)) return false
        handOff(context, to, text)
        return true
    }

    /** Gives [text] to [to]. When that app is not on the phone, opens the page to get it. */
    fun handOff(context: Context, to: ObsiApp, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setPackage(to.pkg)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(EXTRA_FROM, Brand.app.pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(send)
            Toast.makeText(context, "Opened in ${to.title}.", Toast.LENGTH_SHORT).show()
        } catch (e: ActivityNotFoundException) {
            getApp(context, to, "This is a ${to.noun}. Get ${to.title} to open it.")
        }
    }

    /** Opens a sibling app, or the page to get it. */
    fun launch(context: Context, to: ObsiApp) {
        val open = context.packageManager.getLaunchIntentForPackage(to.pkg)
        if (open == null) { getApp(context, to, "Get ${to.title} from its download page."); return }
        context.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens one saved thing in a sibling app. */
    fun openIn(context: Context, to: ObsiApp, id: String, at: String = "") {
        val open = context.packageManager.getLaunchIntentForPackage(to.pkg)
        if (open == null) { getApp(context, to, "Get ${to.title} to open this."); return }
        context.startActivity(
            open.putExtra(EXTRA_OPEN_ID, id).putExtra(EXTRA_OPEN_AT, at).putExtra(EXTRA_FROM, Brand.app.pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun getApp(context: Context, to: ObsiApp, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(to.downloadPage)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
