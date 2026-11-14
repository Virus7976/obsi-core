package app.obsi.core

import android.content.Context

/** The three Obsi apps. Each one is a separate app on the phone, with its own key and updates. */
enum class ObsiApp(
    val title: String,
    val pkg: String,
    val repo: String,
    /** One thing the app saves: "video", "article", "passage". */
    val noun: String,
    val nouns: String,
    /** What the app reads, for a short line under its name. */
    val about: String,
) {
    Tube("ObsiTube", "app.marginalia", "Virus7976/obsidian-web-clipper-YouTube-android-Private", "video", "videos", "YouTube videos"),
    Read("ObsiRead", "app.obsiread", "Virus7976/obsiread", "article", "articles", "Web pages"),
    Bible("Obsi Bible", "app.obsibible", "Virus7976/obsi-bible", "passage", "passages", "The Bible");

    val downloadPage: String get() = "https://github.com/$repo/releases/latest"

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    companion object {
        fun ofPackage(pkg: String): ObsiApp? = entries.firstOrNull { it.pkg == pkg }
    }
}

/**
 * Which Obsi app this is. Each app sets it first thing in Application.onCreate.
 * Shared screens use it for the app's name and words ("3 videos", "3 articles").
 */
object Brand {
    @Volatile
    var app: ObsiApp = detect()

    /** The app whose classes are here. Works in unit tests too, where nothing calls [init]. */
    private fun detect(): ObsiApp {
        fun has(name: String) = runCatching { Class.forName(name, false, Brand::class.java.classLoader); true }.getOrDefault(false)
        return when {
            has("app.marginalia.MarginaliaApp") -> ObsiApp.Tube
            has("app.obsibible.ObsiBibleApp") -> ObsiApp.Bible
            else -> ObsiApp.Read
        }
    }

    fun init(app: ObsiApp) { this.app = app }

    val name: String get() = app.title
    val noun: String get() = app.noun
    val nouns: String get() = app.nouns
    /** "Video data", "Article data". */
    val dataLabel: String get() = noun.replaceFirstChar { it.uppercase() } + " data"

    /** "1 video", "3 videos". */
    fun count(n: Int): String = if (n == 1) "1 $noun" else "$n $nouns"

    /** The other two apps. */
    val others: List<ObsiApp> get() = ObsiApp.entries.filter { it != app }
}
