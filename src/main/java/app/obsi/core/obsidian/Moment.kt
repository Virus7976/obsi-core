package app.obsi.core.obsidian

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Date formats as Obsidian writes them (moment.js): "gggg-[W]ww", "YYYY-MM-DD", "[Week] W, YYYY".
 * Text in [brackets] is kept as it is. Locale weeks (g, w) follow [week]; ISO weeks (G, W) start on Monday.
 */
object Moment {
    private val tokens = listOf(
        "MMMM", "MMM", "Mo", "MM", "M", "DDDD", "DDDo", "DDD", "Do", "DD", "D", "dddd", "ddd", "dd", "do", "d", "E", "e",
        "YYYY", "YY", "gggg", "gg", "GGGG", "GG", "wo", "ww", "w", "Wo", "WW", "W", "Q", "HH", "H", "hh", "h", "mm", "m", "ss", "s", "A", "a",
    )

    private sealed interface Part
    private data class Lit(val text: String) : Part
    private data class Tok(val t: String) : Part

    private fun parse(fmt: String): List<Part> {
        val out = ArrayList<Part>()
        var i = 0
        val lit = StringBuilder()
        fun flush() { if (lit.isNotEmpty()) { out += Lit(lit.toString()); lit.clear() } }
        while (i < fmt.length) {
            val c = fmt[i]
            if (c == '[') {
                val end = fmt.indexOf(']', i + 1)
                if (end > i) { lit.append(fmt, i + 1, end); i = end + 1; continue }
            }
            val t = tokens.firstOrNull { fmt.startsWith(it, i) }
            if (t != null) { flush(); out += Tok(t); i += t.length } else { lit.append(c); i++ }
        }
        flush()
        return out
    }

    private fun ord(n: Int): String {
        val s = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$s"
    }

    fun format(at: LocalDateTime, fmt: String, week: WeekFields): String {
        val d = at.toLocalDate()
        val iso = WeekFields.ISO
        fun p2(n: Int) = n.toString().padStart(2, '0')
        return parse(fmt).joinToString("") { part ->
            when (part) {
                is Lit -> part.text
                is Tok -> when (part.t) {
                    "YYYY" -> d.year.toString()
                    "YY" -> p2(d.year % 100)
                    "gggg" -> d.get(week.weekBasedYear()).toString()
                    "gg" -> p2(d.get(week.weekBasedYear()) % 100)
                    "GGGG" -> d.get(iso.weekBasedYear()).toString()
                    "GG" -> p2(d.get(iso.weekBasedYear()) % 100)
                    "ww" -> p2(d.get(week.weekOfWeekBasedYear()))
                    "w" -> d.get(week.weekOfWeekBasedYear()).toString()
                    "wo" -> ord(d.get(week.weekOfWeekBasedYear()))
                    "WW" -> p2(d.get(iso.weekOfWeekBasedYear()))
                    "W" -> d.get(iso.weekOfWeekBasedYear()).toString()
                    "Wo" -> ord(d.get(iso.weekOfWeekBasedYear()))
                    "MMMM" -> d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                    "MMM" -> d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    "MM" -> p2(d.monthValue)
                    "M" -> d.monthValue.toString()
                    "Mo" -> ord(d.monthValue)
                    "DDDD" -> d.dayOfYear.toString().padStart(3, '0')
                    "DDD" -> d.dayOfYear.toString()
                    "DDDo" -> ord(d.dayOfYear)
                    "DD" -> p2(d.dayOfMonth)
                    "D" -> d.dayOfMonth.toString()
                    "Do" -> ord(d.dayOfMonth)
                    "dddd" -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                    "ddd" -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    "dd" -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).take(2)
                    "d" -> (d.dayOfWeek.value % 7).toString()
                    "do" -> ord(d.dayOfWeek.value % 7)
                    "E" -> d.dayOfWeek.value.toString()
                    "e" -> (d.get(week.dayOfWeek()) - 1).toString()
                    "Q" -> ((d.monthValue - 1) / 3 + 1).toString()
                    "HH" -> p2(at.hour)
                    "H" -> at.hour.toString()
                    "hh" -> p2((at.hour + 11) % 12 + 1)
                    "h" -> ((at.hour + 11) % 12 + 1).toString()
                    "mm" -> p2(at.minute)
                    "m" -> at.minute.toString()
                    "ss" -> p2(at.second)
                    "s" -> at.second.toString()
                    "A" -> if (at.hour < 12) "AM" else "PM"
                    "a" -> if (at.hour < 12) "am" else "pm"
                    else -> part.t
                }
            }
        }
    }

    fun format(d: LocalDate, fmt: String, week: WeekFields): String = format(d.atStartOfDay(), fmt, week)

    /** A pattern that matches names written in [fmt], to find notes already made with it. */
    fun regex(fmt: String): Regex = Regex(
        "^" + parse(fmt).joinToString("") { part ->
            when (part) {
                is Lit -> Regex.escape(part.text)
                is Tok -> when (part.t) {
                    "YYYY", "gggg", "GGGG" -> "\\d{4}"
                    "YY", "gg", "GG", "ww", "WW", "MM", "DD", "HH", "hh", "mm", "ss" -> "\\d{2}"
                    "DDDD" -> "\\d{3}"
                    "MMMM", "MMM", "dddd", "ddd", "dd" -> "[A-Za-z]+"
                    "Mo", "Do", "DDDo", "wo", "Wo", "do" -> "\\d{1,3}(?:st|nd|rd|th)"
                    "A", "a" -> "[AaPp][Mm]"
                    else -> "\\d{1,3}"
                }
            }
        } + "$",
    )

    /** The week rules for "locale", "monday" or "sunday". Locale weeks are what Obsidian uses by default. */
    fun week(start: String, locale: Locale = Locale.getDefault()): WeekFields = when (start.lowercase()) {
        "monday" -> WeekFields.of(DayOfWeek.MONDAY, 4)
        "sunday" -> WeekFields.of(DayOfWeek.SUNDAY, 1)
        else -> WeekFields.of(locale)
    }

    /** The first day of the week [d] is in. */
    fun startOfWeek(d: LocalDate, week: WeekFields): LocalDate = d.with(week.dayOfWeek(), 1)
}
