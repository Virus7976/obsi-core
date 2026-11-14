package app.obsi.core.link

import java.net.URI

/** How the Obsi apps tell that two links name the same video or page. */
object Sources {
    private val tracking = Regex("^(utm_.*|fbclid|gclid|dclid|mc_cid|mc_eid|igshid|ref_src|ref|cmpid|_hsenc|_hsmi|mkt_tok|yclid|si)$", RegexOption.IGNORE_CASE)
    private val idPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val idInLink = Regex("""(?:youtu\.be/|youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/|embed/|v/)|youtube-nocookie\.com/embed/)([A-Za-z0-9_-]{11})""")

    /**
     * The same page always gets the same address: no tracking bits, no #part, no "www.", and
     * the scheme and host in lower case. Two shares of one article then find the same copy.
     */
    fun canonical(url: String): String {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return url.trim()
        val host = u.host?.lowercase()?.removePrefix("www.") ?: return url.trim()
        val query = u.rawQuery?.split('&')?.filter { p -> p.isNotBlank() && !tracking.matches(p.substringBefore('=')) }?.joinToString("&")
        val path = (u.rawPath ?: "").ifEmpty { "/" }.let { if (it.length > 1) it.trimEnd('/') else it }
        return buildString {
            append("https://").append(host)
            if (u.port != -1 && u.port != 443 && u.port != 80) append(':').append(u.port)
            append(path)
            if (!query.isNullOrEmpty()) append('?').append(query)
        }
    }

    /** Pulls a video id out of any normal YouTube link, or a bare id. */
    fun youTubeId(input: String): String? {
        val s = input.trim()
        if (idPattern.matches(s)) return s
        return idInLink.find(s)?.groupValues?.get(1)
    }

    /** True when [a] and [b] are the same video, or the same page. */
    fun same(a: String, b: String): Boolean {
        if (a.trim() == b.trim()) return true
        val x = youTubeId(a)
        val y = youTubeId(b)
        if (x != null || y != null) return x == y
        return canonical(a) == canonical(b)
    }

    /** Seconds as 1:05 or 1:02:03. */
    fun time(sec: Double): String {
        val t = sec.toLong().coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
