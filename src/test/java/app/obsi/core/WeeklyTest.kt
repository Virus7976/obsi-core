package app.obsi.core

import app.obsi.core.obsidian.Moment
import app.obsi.core.obsidian.WeeklyText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

class WeeklyTest {
    private val sunday = Moment.week("sunday")
    private val canada = Moment.week("locale", Locale("en", "CA"))
    private val day = LocalDate.of(2026, 11, 19)

    // A weekly template like the ones people use, with a table-of-contents block and no trailing newline.
    private val template = "---\ntags:\n  - weekly\n---\n```table-of-contents\n```\n\n# Weekly Objectives\n\n# Key Highlights & Wins\n\n# Reflection & Review\n\n# Notes\n\n# Links"

    @Test fun weekNames() {
        assertEquals("2026-W47", Moment.format(day, "gggg-[W]ww", sunday))
        assertEquals("2026-W47", Moment.format(day, "gggg-[W]ww", canada))
        // The week before, which is the newest note in a real vault: Sunday 8 to Saturday 14 November.
        assertEquals("2026-W46", Moment.format(LocalDate.of(2026, 11, 14), "gggg-[W]ww", sunday))
        assertEquals("2026-W47", Moment.format(LocalDate.of(2026, 11, 15), "gggg-[W]ww", sunday))
        assertEquals("Week 47, 2026", Moment.format(day, "[Week] W, GGGG", sunday))
        assertEquals("2026-11-19 Thursday", Moment.format(day, "YYYY-MM-DD dddd", sunday))
        assertEquals("Nov 19th", Moment.format(day, "MMM Do", sunday))
        // An ISO week year is not the calendar year at the edges.
        assertEquals("2026-W01", Moment.format(LocalDate.of(2025, 12, 29), "GGGG-[W]WW", sunday))
        assertEquals(LocalDate.of(2026, 11, 15), Moment.startOfWeek(day, sunday))
        assertEquals(LocalDate.of(2026, 11, 16), Moment.startOfWeek(day, Moment.week("monday")))
    }

    @Test fun findsNotesByName() {
        val r = Moment.regex("gggg-[W]ww")
        assertTrue(r.matches("2026-W46"))
        assertFalse(r.matches("2026-11-19"))
        assertFalse(r.matches("Week 4 Essay"))
        assertTrue(Moment.regex("[Week] W, GGGG").matches("Week 7, 2026"))
    }

    @Test fun fillsInTemplates() {
        val now = LocalDateTime.of(2026, 11, 19, 9, 30)
        val start = LocalDate.of(2026, 11, 15)
        val t = "# {{title}}\n{{date:YYYY-MM-DD}} to {{saturday:MM-DD}}\nNext {{date+1w:gggg-[W]ww}}\n" +
            "<%* const x = 1 %>\nTitle <% tp.file.title %>\nMon <% tp.date.weekday(\"YYYY-MM-DD\", 1, tp.file.title, \"gggg-[W]ww\") %>\n" +
            "Made <% tp.file.creation_date(\"YYYY-MM-DD\") %> <% tp.user.thing() %>end"
        val out = WeeklyText.render(t, "2026-W47", start, sunday, now)
        assertEquals(
            "# 2026-W47\n2026-11-15 to 11-21\nNext 2026-W48\nTitle 2026-W47\nMon 2026-11-16\nMade 2026-11-19 end",
            out,
        )
    }

    @Test fun placesUnderTheHeadingAndOnlyOnce() {
        val body = "**This week** · 1h 00m"
        val once = WeeklyText.place(template, body, "Key Highlights & Wins")
        assertTrue(once.contains("# Key Highlights & Wins\n\n${WeeklyText.START}\n$body\n${WeeklyText.END}\n\n# Reflection & Review"))
        // The rest of the note is as it was.
        assertEquals(template, once.replace("${WeeklyText.START}\n$body\n${WeeklyText.END}\n\n", ""))
        // A second write replaces the first, wherever it is.
        val twice = WeeklyText.place(once, "**This week** · 2h 00m", "Key Highlights & Wins")
        assertEquals(1, Regex(Regex.escape(WeeklyText.START)).findAll(twice).count())
        assertTrue(twice.contains("2h 00m") && !twice.contains("1h 00m"))
    }

    @Test fun keepsWhatYouWroteUnderTheHeading() {
        val note = "# Key Highlights & Wins\n\n - Very productive week\n# Reflection & Review\n\nMine."
        val out = WeeklyText.place(note, "X", "key highlights & wins")
        assertEquals("# Key Highlights & Wins\n\n - Very productive week\n\n${WeeklyText.START}\nX\n${WeeklyText.END}\n\n# Reflection & Review\n\nMine.", out)
    }

    @Test fun goesAtTheEndWithNoHeading() {
        val out = WeeklyText.place(template, "X", "")
        assertTrue(out.endsWith("# Links\n\n${WeeklyText.START}\n## From the Obsi apps\n\nX\n${WeeklyText.END}\n"))
        // A heading that is not in the note also goes to the end.
        assertTrue(WeeklyText.place(template, "X", "Nope").contains("## From the Obsi apps"))
    }

    @Test fun readsHeadingsNotCode() {
        val t = "---\ntitle: # not\n---\n```\n# in code\n```\n# Real\n## Sub"
        assertEquals(listOf("Real", "Sub"), WeeklyText.headingNames(t))
        assertEquals("Key Highlights & Wins", WeeklyText.guessHeading(template))
    }

    @Test fun windowsLineEndsStay() {
        val out = WeeklyText.place("# A\r\n\r\n# B", "X", "A")
        assertFalse(out.replace("\r\n", "").contains("\n"))
    }

    @Test fun body() {
        assertEquals("", WeeklyText.body(listOf(WeeklyText.AppWeek("ObsiTube", 0, 0, 0)), emptyList(), emptyList()))
        val b = WeeklyText.body(
            listOf(WeeklyText.AppWeek("ObsiTube", 3720, 2, 1), WeeklyText.AppWeek("ObsiRead", 600, 1, 0)),
            listOf(WeeklyText.ItemTime("A | video", "ObsiTube", 3000), WeeklyText.ItemTime("Short", "ObsiRead", 30)),
            listOf(WeeklyText.Mark("A | video", "ObsiTube", "Sleep  is\nkey", "my note")),
        )
        assertTrue(b.startsWith("**This week** · 1h 12m · 3 finished · 1 highlight"))
        assertTrue(b.contains("| ObsiTube | 1h 02m | 2 | 1 |"))
        assertTrue(b.contains("- A | video · 50m · ObsiTube"))
        // An app with under a minute and nothing else is left out, and so is an empty column.
        val c = WeeklyText.body(
            listOf(WeeklyText.AppWeek("ObsiLearn", 1680, 6, 0), WeeklyText.AppWeek("Obsi Bible", 20, 0, 0), WeeklyText.AppWeek("ObsiRead", 300, 0, 0)),
            emptyList(), emptyList(),
        )
        assertTrue(c.contains("| App | Time | Finished |\n| --- | ---: | ---: |\n| ObsiLearn | 28m | 6 |\n| ObsiRead | 5m | 0 |"))
        assertFalse(c.contains("Bible"))
        // The total is the sum of the rows as they are shown: 29m + 1m = 30m.
        val d = WeeklyText.body(listOf(WeeklyText.AppWeek("ObsiLearn", 1730, 0, 0), WeeklyText.AppWeek("Obsi Bible", 40, 1, 0)), emptyList(), emptyList())
        assertTrue(d, d.startsWith("**This week** · 30m · 1 finished"))
        assertTrue(d, d.contains("| ObsiLearn | 29m | 0 |") && d.contains("| Obsi Bible | 1m | 1 |"))
        assertFalse(b.contains("Short"))
        assertTrue(b.contains("    - Sleep is key · *my note*"))
    }
}
