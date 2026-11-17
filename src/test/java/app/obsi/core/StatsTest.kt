package app.obsi.core

import app.obsi.core.data.Activity
import app.obsi.core.data.DayActivity
import app.obsi.core.share.SharedHighlight
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class StatsTest {
    private val today = LocalDate.of(2026, 11, 16)
    private fun d(back: Long) = today.minusDays(back).toString()
    private fun hl(app: ObsiApp, back: Long) = SharedHighlight(
        app, "id", "t", "words", "", 0, "0", "", today.minusDays(back).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + 3_600_000,
    )

    @Test fun streakJoinsEveryApp() {
        // Tube today and yesterday, Read the day before, a highlight in Learn the day before that.
        val days = mapOf(
            ObsiApp.Tube to mapOf(d(0) to DayActivity(300), d(1) to DayActivity(120)),
            ObsiApp.Read to mapOf(d(2) to DayActivity(0, finished = 1)),
        )
        val s = Activity.build(days, listOf(hl(ObsiApp.Learn, 3)), today, ZoneOffset.UTC)
        assertEquals(4, s.streak)
        assertEquals(420, s.weekSec)
        assertEquals(1, s.totalFinished)
        assertEquals(1, s.totalHighlights)
    }

    @Test fun anEmptyTodayKeepsYesterdaysStreak() {
        val days = mapOf(ObsiApp.Tube to mapOf(d(1) to DayActivity(600), d(2) to DayActivity(600)))
        assertEquals(2, Activity.build(days, emptyList(), today, ZoneOffset.UTC).streak)
    }

    @Test fun lessThanAMinuteDoesNotCount() {
        val days = mapOf(ObsiApp.Tube to mapOf(d(0) to DayActivity(30), d(5) to DayActivity(900), d(6) to DayActivity(900)))
        val s = Activity.build(days, emptyList(), today, ZoneOffset.UTC)
        assertEquals(0, s.streak)
        assertEquals(2, s.bestStreak)
    }

    @Test fun durations() {
        assertEquals("0m", Activity.duration(30))
        assertEquals("12m", Activity.duration(720))
        assertEquals("2h 05m", Activity.duration(7500))
    }
}
