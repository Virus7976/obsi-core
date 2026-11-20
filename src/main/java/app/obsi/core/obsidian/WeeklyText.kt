package app.obsi.core.obsidian

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.WeekFields

/**
 * The text work for the weekly note, with no files: fill in a new note from its template, and put the
 * apps' part into a note without touching anything you wrote.
 */
object WeeklyText {
    const val START = "<!-- obsi:week start -->"
    const val END = "<!-- obsi:week end -->"

    /**
     * Fills in a template the way Obsidian would when it makes the note: the core {{date}} and
     * {{title}} tags, Periodic Notes' {{monday:...}} and {{date+1d:...}}, and Templater's common
     * tp.date and tp.file calls. Templater scripts (<%* %>) cannot run here, so they are left out.
     */
    fun render(template: String, title: String, weekStart: LocalDate, week: WeekFields, now: LocalDateTime = LocalDateTime.now()): String {
        val nowAt = weekStart.atTime(now.toLocalTime())
        var t = template.replace("\r\n", "\n")

        // Core templates and Periodic Notes.
        t = Regex("""\{\{\s*(date|time|title|sunday|monday|tuesday|wednesday|thursday|friday|saturday)\s*(?:([+-])\s*(\d+)\s*([dwmy]))?\s*(?::([^}]*))?\}\}""", RegexOption.IGNORE_CASE)
            .replace(t) { m ->
                val what = m.groupValues[1].lowercase()
                if (what == "title") return@replace title
                var at = when (what) {
                    "date", "time" -> nowAt
                    else -> Moment.startOfWeek(weekStart, week).let { s ->
                        val want = DayOfWeek.valueOf(what.uppercase())
                        (0..6).map { s.plusDays(it.toLong()) }.first { it.dayOfWeek == want }.atTime(now.toLocalTime())
                    }
                }
                val n = m.groupValues[3].toLongOrNull()
                if (n != null) {
                    val k = if (m.groupValues[2] == "-") -n else n
                    at = when (m.groupValues[4].lowercase()) { "d" -> at.plusDays(k); "w" -> at.plusWeeks(k); "m" -> at.plusMonths(k); else -> at.plusYears(k) }
                }
                val fmt = m.groupValues[5].trim().ifBlank { if (what == "time") "HH:mm" else "YYYY-MM-DD" }
                Moment.format(at, fmt, week)
            }

        // Templater: a script on its own line goes with its line.
        t = Regex("""(?m)^[ \t]*<%[*][\s\S]*?%>[ \t]*\n?""").replace(t, "")
        t = Regex("""<%[*][\s\S]*?%>""").replace(t, "")
        t = Regex("""<%[-_]?\s*([\s\S]*?)\s*[-_]?%>""").replace(t) { m -> templater(m.groupValues[1], title, weekStart, week, now) ?: "" }
        return t
    }

    private fun str(args: List<String>, i: Int): String? = args.getOrNull(i)?.trim()?.let { a ->
        if (a.length >= 2 && (a.first() == '"' || a.first() == '\'' || a.first() == '`') && a.last() == a.first()) a.substring(1, a.length - 1) else null
    }

    private fun args(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var q: Char? = null
        val b = StringBuilder()
        for (c in s) {
            when {
                q != null -> { b.append(c); if (c == q) q = null }
                c == '"' || c == '\'' || c == '`' -> { q = c; b.append(c) }
                c == '(' -> { depth++; b.append(c) }
                c == ')' -> { depth--; b.append(c) }
                c == ',' && depth == 0 -> { out += b.toString(); b.clear() }
                else -> b.append(c)
            }
        }
        if (b.isNotBlank()) out += b.toString()
        return out
    }

    /** One `<% %>` tag. Null when it is not one this knows; it is then left out. */
    private fun templater(expr: String, title: String, weekStart: LocalDate, week: WeekFields, now: LocalDateTime): String? {
        val e = expr.trim().removeSuffix(";")
        if (e == "tp.file.title") return title
        val m = Regex("""^tp\.(date|file)\.(\w+)\s*\(([\s\S]*)\)$""").find(e) ?: return null
        val fn = m.groupValues[2]
        val a = args(m.groupValues[3])
        val fmt = str(a, 0)?.ifBlank { null } ?: "YYYY-MM-DD"
        val today = now
        return when (m.groupValues[1] + "." + fn) {
            "date.now" -> {
                val off = a.getOrNull(1)?.trim()
                // The reference date, when the template gives the note title as one, is the note's week.
                val ref = if (a.size >= 3) weekStart.atTime(now.toLocalTime()) else today
                val days = off?.toLongOrNull() ?: Regex("""^["'`]?P(-?\d+)D["'`]?$""", RegexOption.IGNORE_CASE).find(off.orEmpty())?.groupValues?.get(1)?.toLongOrNull() ?: 0
                Moment.format(ref.plusDays(days), fmt, week)
            }
            "date.tomorrow" -> Moment.format(today.plusDays(1), fmt, week)
            "date.yesterday" -> Moment.format(today.minusDays(1), fmt, week)
            "date.weekday" -> {
                val n = a.getOrNull(1)?.trim()?.toLongOrNull() ?: 0
                val ref = if (a.size >= 3) weekStart else today.toLocalDate()
                Moment.format(Moment.startOfWeek(ref, week).plusDays(n).atTime(now.toLocalTime()), fmt, week)
            }
            "file.creation_date", "file.last_modified_date" -> Moment.format(today, str(a, 0)?.ifBlank { null } ?: "YYYY-MM-DD HH:mm", week)
            else -> null
        }
    }

    // ---------- putting the apps' part in the note ----------

    private data class Heading(val line: Int, val level: Int, val text: String)

    private fun headings(lines: List<String>): List<Heading> {
        val out = ArrayList<Heading>()
        var fence: String? = null
        var front = lines.firstOrNull()?.trim() == "---"
        lines.forEachIndexed { i, raw ->
            val l = raw.trimEnd()
            if (front) { if (i > 0 && l.trim() == "---") front = false; return@forEachIndexed }
            val f = Regex("""^\s*(```+|~~~+)""").find(l)?.groupValues?.get(1)
            if (f != null) { fence = if (fence == null) f else if (f.startsWith(fence!!)) null else fence; return@forEachIndexed }
            if (fence != null) return@forEachIndexed
            Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""").find(l)?.let { out += Heading(i, it.groupValues[1].length, it.groupValues[2].trim()) }
        }
        return out
    }

    /** The headings of a note, in order, for the "put it under" picker. */
    fun headingNames(note: String): List<String> = headings(note.replace("\r\n", "\n").split('\n')).map { it.text }.distinct()

    /** The heading this guesses you want, from what notes usually have. */
    fun guessHeading(note: String): String {
        val hs = headingNames(note)
        val wanted = listOf("obsi", "learn", "read", "watch", "media", "highlight", "notes", "review")
        return wanted.firstNotNullOfOrNull { w -> hs.firstOrNull { it.contains(w, ignoreCase = true) } }.orEmpty()
    }

    /**
     * [note] with the apps' part set to [body]. A part written before is replaced where it is, even
     * if you moved it. Else it goes at the end of the section under [heading], after what you wrote
     * there. Else at the end of the note, under a heading of its own. Nothing else changes.
     */
    fun place(note: String, body: String, heading: String): String {
        val crlf = note.contains("\r\n")
        val text = note.replace("\r\n", "\n")
        val s = text.indexOf(START)
        val e = text.indexOf(END)
        val out = if (s >= 0 && e > s) {
            text.substring(0, s) + START + "\n" + body.trim() + "\n" + END + text.substring(e + END.length)
        } else {
            val lines = text.split('\n')
            val hs = headings(lines)
            val h = heading.trim().takeIf { it.isNotBlank() }?.let { want -> hs.firstOrNull { it.text.equals(want, ignoreCase = true) } }
            if (h != null) {
                val next = hs.firstOrNull { it.line > h.line && it.level <= h.level }?.line ?: lines.size
                // After the last line with something on it in the section.
                var last = next - 1
                while (last > h.line && lines[last].isBlank()) last--
                val before = lines.subList(0, last + 1).joinToString("\n")
                val after = lines.subList(last + 1, lines.size).dropWhile { it.isBlank() }.joinToString("\n")
                val block = "$START\n${body.trim()}\n$END"
                before + "\n\n" + block + "\n" + if (after.isEmpty()) "" else "\n" + after
            } else {
                val block = "$START\n## From the Obsi apps\n\n${body.trim()}\n$END\n"
                text.trimEnd() + (if (text.isBlank()) "" else "\n\n") + block
            }
        }
        return if (crlf) out.replace("\n", "\r\n") else out
    }

    // ---------- the apps' part ----------

    data class AppWeek(val app: String, val sec: Int, val finished: Int, val highlights: Int)
    data class ItemTime(val title: String, val app: String, val sec: Int)
    data class Mark(val item: String, val app: String, val text: String, val note: String)

    fun duration(sec: Int): String {
        val m = sec / 60
        return when {
            m < 1 -> if (sec > 0) "<1m" else "0m"
            m < 60 -> "${m}m"
            else -> "${m / 60}h ${(m % 60).toString().padStart(2, '0')}m"
        }
    }

    private fun clean(s: String) = s.replace(Regex("""\s+"""), " ").trim()
    private fun cell(s: String) = clean(s).replace("|", "\\|")

    /** The Markdown for one week. Empty when nothing happened in it. */
    fun body(apps: List<AppWeek>, items: List<ItemTime>, marks: List<Mark>, maxMarks: Int = 15): String {
        val used = apps.filter { it.sec >= 60 || it.finished > 0 || it.highlights > 0 }
        if (used.isEmpty()) return ""
        // Each row is rounded to the nearest minute, and the total is made from the rows, so the sums add up.
        fun near(s: Int) = (s + 30) / 60 * 60
        val sec = used.sumOf { near(it.sec) }
        val fin = used.sumOf { it.finished }
        val hl = used.sumOf { it.highlights }
        val sb = StringBuilder()
        val head = listOfNotNull(
            duration(sec).takeIf { sec > 0 },
            "$fin finished".takeIf { fin > 0 },
            (if (hl == 1) "1 highlight" else "$hl highlights").takeIf { hl > 0 },
        )
        sb.append("**This week** · ").append(head.joinToString(" · ")).append("\n\n")
        if (used.size > 1) {
            // A column with nothing in it is left out.
            val cols = listOfNotNull(
                Pair("Time") { a: AppWeek -> duration(near(a.sec)) }.takeIf { sec > 0 },
                Pair("Finished") { a: AppWeek -> a.finished.toString() }.takeIf { fin > 0 },
                Pair("Highlights") { a: AppWeek -> a.highlights.toString() }.takeIf { hl > 0 },
            )
            sb.append("| App | ").append(cols.joinToString(" | ") { it.first }).append(" |\n")
            sb.append("| --- | ").append(cols.joinToString(" | ") { "---:" }).append(" |\n")
            used.sortedByDescending { it.sec }.forEach { a -> sb.append("| ${cell(a.app)} | ").append(cols.joinToString(" | ") { it.second(a) }).append(" |\n") }
            sb.append('\n')
        }
        val top = items.filter { it.sec >= 60 }.sortedByDescending { it.sec }.take(5)
        if (top.isNotEmpty()) {
            sb.append("**Most time**\n")
            top.forEach { sb.append("- ${clean(it.title)} · ${duration(it.sec)}").append(if (used.size > 1) " · ${it.app}" else "").append('\n') }
            sb.append('\n')
        }
        if (marks.isNotEmpty()) {
            sb.append("**Highlights**\n")
            var shown = 0
            for ((key, group) in marks.groupBy { it.item to it.app }) {
                if (shown >= maxMarks) break
                sb.append("- ${clean(key.first)}").append(if (used.size > 1) " · ${key.second}" else "").append('\n')
                for (m in group) {
                    if (shown >= maxMarks) break
                    val t = clean(m.text).let { if (it.length > 280) it.take(277).trimEnd() + "…" else it }
                    val n = clean(m.note)
                    sb.append("    - ").append(t.ifBlank { n })
                    if (t.isNotBlank() && n.isNotBlank()) sb.append(" · *").append(n).append('*')
                    sb.append('\n')
                    shown++
                }
            }
            if (marks.size > shown) sb.append("\n*and ${marks.size - shown} more*\n")
        }
        return sb.toString().trim()
    }
}
