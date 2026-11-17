package app.obsi.core.data

/** A web server's answer code, in words a person can act on. The same words in every Obsi app. */
object HttpWords {
    /** [who] is "The server", "The site", "Google Drive" and so on. */
    fun say(code: Int, who: String = "The server"): String = when (code) {
        401 -> "$who did not take that user name and password."
        403 -> "$who would not let you in. Check that this account may read it."
        404 -> "$who has nothing at that address. Check it, letter for letter."
        408, 504 -> "$who took too long to answer. Try again."
        429 -> "$who is busy. Wait a minute, then try again."
        in 500..599 -> "$who had a problem of its own (code $code). Try again later."
        else -> "$who answered with code $code."
    }

    /**
     * What went wrong, in plain words. No-internet, time-out and other low-level errors get words a
     * person can act on. A message the app wrote itself is kept. [fallback] covers the rest.
     */
    fun error(e: Throwable?, fallback: String): String {
        var c: Throwable? = e
        while (c != null) {
            when (c) {
                is java.net.UnknownHostException -> return "No internet, or the address is wrong. Check your connection, then try again."
                is java.net.SocketTimeoutException -> return "It took too long to answer. Try again."
                is java.net.ConnectException -> return "Could not connect. Check the address and your connection."
                is javax.net.ssl.SSLException -> return "The secure connection failed. Check the address, or try again later."
                is OutOfMemoryError -> return "That is too big to open on this phone."
            }
            c = c.cause
        }
        val m = e?.message?.trim().orEmpty()
        // Our own messages are whole sentences. Raw ones look like code ("java.io…", "Expected BEGIN_OBJECT…").
        val raw = m.isEmpty() || m.contains("Exception") || m.contains("java.") || m.contains("kotlin.") ||
            m.startsWith("Expected ") || m.contains("Unexpected JSON") || m.contains("errno") || !m.first().isUpperCase()
        return if (raw) fallback else m
    }
}
