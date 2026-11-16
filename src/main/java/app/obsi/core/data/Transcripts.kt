package app.obsi.core.data

/** Transcripts for every Obsi app: reading subtitle files (.srt, .vtt), and turning cues into lines you read. */
object Transcripts {
    /** Chapters from timestamps in a description: must start at 0:00, need 3 or more. */
    fun parseChapters(description: String): List<Chapter> {
        val rx = Regex("""^\s*[\[(]?((?:\d{1,2}:)?\d{1,2}:\d{2})[\])]?\s*[-–—:|]?\s*(.+?)\s*$""")
        val found = description.lines().mapNotNull { line ->
            val m = rx.find(line) ?: return@mapNotNull null
            val secs = m.groupValues[1].split(":").map { it.toInt() }.fold(0) { a, b -> a * 60 + b }
            Chapter(secs.toDouble(), m.groupValues[2])
        }
        if (found.size < 3 || found.first().start != 0.0) return emptyList()
        return found.sortedBy { it.start }
    }

    /** Glue short caption cues into readable lines. */
    fun toLines(cues: List<Cue>, lengthSetting: Int, chapters: List<Chapter>): List<Line> {
        val (target, max) = when (lengthSetting) {
            0 -> 80 to 140
            2 -> 280 to 460
            else -> 160 to 260
        }
        val out = ArrayList<Line>()
        val sb = StringBuilder()
        var start = 0.0
        var end = 0.0
        var chapterIdx = -1
        fun flush() {
            if (sb.isNotEmpty()) {
                out += Line(start, end, sb.toString().trim())
                sb.clear()
            }
        }
        for (c in cues) {
            val ci = chapters.indexOfLast { it.start <= c.start + 0.5 }
            if (ci != chapterIdx) {
                flush()
                chapterIdx = ci
            }
            if (sb.isNotEmpty() && c.start - end > 2.5) flush()
            if (sb.isEmpty()) start = c.start else sb.append(' ')
            sb.append(c.text)
            end = c.start + c.dur
            val done = Regex("[.?!][\"')\\]]?$").containsMatchIn(sb)
            if ((sb.length >= target && done) || sb.length >= max) flush()
        }
        flush()
        return out
    }

    fun formatTime(sec: Double): String {
        val t = sec.toInt().coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private val stamp = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")

    private fun sec(m: MatchResult): Double {
        val (h, mi, s, ms) = m.destructured
        return (h.toIntOrNull() ?: 0) * 3600.0 + mi.toInt() * 60 + s.toInt() + ms.padEnd(3, '0').toInt() / 1000.0
    }

    private val tag = Regex("<[^>]+>")
    private val brace = Regex("""\{[^}]*\}""")
    private val space = Regex("""\s+""")
    private val blank = Regex("""\n\s*\n""")

    /**
     * Reads an .srt or .vtt file. Tags like <i> and cue settings are dropped. Rolling captions,
     * where each cue repeats the one before, keep only the new words.
     */
    fun parseSubtitles(text: String, lang: String = ""): TranscriptData {
        val cues = ArrayList<Cue>()
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(blank)
        for (b in blocks) {
            val lines = b.lines().filter { it.isNotBlank() }
            val ti = lines.indexOfFirst { "-->" in it }
            if (ti < 0) continue
            val parts = lines[ti].split("-->")
            val a = stamp.find(parts[0]) ?: continue
            val z = stamp.find(parts.getOrElse(1) { "" }) ?: continue
            val start = sec(a)
            val end = sec(z)
            val words = lines.drop(ti + 1).joinToString(" ")
                .replace(tag, "").replace(brace, "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
                .replace(space, " ").trim()
            if (words.isEmpty()) continue
            val prev = cues.lastOrNull()
            val fresh = when {
                prev == null -> words
                words == prev.text -> ""
                words.startsWith(prev.text) -> words.substring(prev.text.length).trim()
                else -> words
            }
            if (fresh.isEmpty()) continue
            cues += Cue(start, (end - start).coerceAtLeast(0.2), fresh)
        }
        return TranscriptData(lang = lang, auto = false, cues = cues.sortedBy { it.start })
    }

    /** Writes cues as a .vtt file, so a transcript made in the app can be saved next to the video. */
    fun toVtt(data: TranscriptData): String = buildString {
        append("WEBVTT\n\n")
        fun t(s: Double): String {
            val ms = (s * 1000).toLong().coerceAtLeast(0)
            return "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        }
        for (c in data.cues) append(t(c.start)).append(" --> ").append(t(c.start + c.dur)).append('\n').append(c.text).append("\n\n")
    }
}
