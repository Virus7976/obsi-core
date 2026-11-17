package app.obsi.core

import app.obsi.core.data.DayActivity
import app.obsi.core.data.StatsData
import app.obsi.core.data.StatsRange
import app.obsi.core.data.StatsView
import app.obsi.core.data.Transcripts
import app.obsi.core.share.SharedHighlight
import app.obsi.core.share.SharedSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class StatsViewTest {
    private val today = LocalDate.of(2026, 11, 17)
    private fun d(back: Long) = today.minusDays(back).toString()
    private fun hl(app: ObsiApp, back: Long, title: String = "Talk", color: Long = 0xFFF2C14E) = SharedHighlight(
        app, "id", title, "words", "", color, "0", "", today.minusDays(back).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + 3_600_000,
    )

    private val data = StatsData(
        days = mapOf(
            ObsiApp.Tube to mapOf(
                d(0) to DayActivity(600, hours = mapOf("21" to 600), items = mapOf("Golden hour" to 600)),
                d(3) to DayActivity(300, finished = 1),
                d(8) to DayActivity(1200),
            ),
            ObsiApp.Read to mapOf(d(1) to DayActivity(900, hours = mapOf("8" to 900), items = mapOf("An article" to 900))),
        ),
        highlights = listOf(hl(ObsiApp.Tube, 0, "Golden hour"), hl(ObsiApp.Tube, 0, "Golden hour"), hl(ObsiApp.Read, 40, "Old", 0xFF00FF00)),
    )

    @Test fun weekCountsOnlyTheLastSevenDaysAndComparesToTheWeekBefore() {
        val v = StatsView.of(data, StatsRange.Week, today = today, zone = ZoneOffset.UTC)
        assertEquals(1800, v.totalSec)
        assertEquals(1200, v.previousSec)
        assertEquals(7, v.bars.size)
        assertTrue(v.bars.last().now)
        assertEquals(1, v.finished)
        assertEquals(2, v.highlights)
        assertEquals(3, v.activeDays)
    }

    @Test fun pickingAnAppLeavesTheOthersOut() {
        val v = StatsView.of(data, StatsRange.Week, setOf(ObsiApp.Read), today = today, zone = ZoneOffset.UTC)
        assertEquals(900, v.totalSec)
        assertEquals(0, v.highlights)
        assertEquals(setOf(ObsiApp.Read), v.perApp.keys)
    }

    @Test fun hoursAndTopItemsAddUp() {
        val v = StatsView.of(data, StatsRange.Week, today = today, zone = ZoneOffset.UTC)
        assertEquals(600, v.hours[21])
        assertEquals(900, v.hours[8])
        assertEquals("An article", v.top.first().title)
        assertEquals("Golden hour", v.mostHighlighted.first().title)
        assertEquals(2, v.mostHighlighted.first().count)
    }

    @Test fun allTimeHasNoComparisonAndCountsEverything() {
        val v = StatsView.of(data, StatsRange.All, today = today, zone = ZoneOffset.UTC)
        assertNull(v.previousSec)
        assertEquals(3000, v.totalSec)
        assertEquals(3, v.highlights)
        assertEquals(2, v.colors.size)
    }

    @Test fun yearHasTwelveMonthBars() {
        val v = StatsView.of(data, StatsRange.Year, today = today, zone = ZoneOffset.UTC)
        assertEquals(13, v.bars.size.coerceAtLeast(12).coerceAtMost(13))
        assertEquals(3000, v.bars.sumOf { it.total })
    }

    @Test fun podcastJsonTranscriptsAreRead() {
        val t = Transcripts.parseAny("""{"version":"1.0.0","segments":[{"startTime":0.5,"endTime":2.0,"body":"Hello there"},{"startTime":2.0,"endTime":3.5,"body":"and welcome"}]}""")
        assertEquals(2, t.cues.size)
        assertEquals(0.5, t.cues[0].start, 0.001)
        assertEquals("and welcome", t.cues[1].text)
    }

    @Test fun searchFindsHighlightsByEveryWord() {
        val all = listOf(
            hl(ObsiApp.Tube, 0, "Light").copy(text = "Soft light hides small faults"),
            hl(ObsiApp.Tube, 0, "Other").copy(text = "Hard light shows every line"),
        )
        val hits = SharedSearch.highlights("soft faults", all)
        assertEquals(1, hits.size)
        assertEquals("Light", hits[0].title)
    }
}
