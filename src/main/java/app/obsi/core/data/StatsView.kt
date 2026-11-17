package app.obsi.core.data

import app.obsi.core.ObsiApp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** How far back the stats page looks. */
enum class StatsRange(val label: String, val days: Long) {
    Week("Week", 7), Month("Month", 30), Year("Year", 365), All("All time", Long.MAX_VALUE),
}

/** One bar of the chart: its label and the seconds of each app. [now] marks the bar for today or this month. */
data class StatsBar(val label: String, val perApp: Map<ObsiApp, Int>, val now: Boolean, val day: LocalDate) {
    val total: Int get() = perApp.values.sum()
}

/** A thing you spent time on: a video, an article, a lesson or a chapter, by its title. */
data class TopItem(val title: String, val app: ObsiApp, val sec: Int)

/** A thing you highlighted most in. */
data class TopHighlighted(val title: String, val app: ObsiApp, val count: Int)

/** The stats page for one range and some apps. Everything here is worked out by [StatsView.of]. */
data class StatsView(
    val range: StatsRange,
    val totalSec: Int,
    /** The same length of time just before, to compare. Null for all time. */
    val previousSec: Int?,
    val finished: Int,
    val highlights: Int,
    /** Days in the range with a minute, a highlight or something finished. */
    val activeDays: Int,
    val bars: List<StatsBar>,
    /** Seconds by hour of the day, 0 to 23. All zero for days from before hours were kept. */
    val hours: List<Int>,
    val top: List<TopItem>,
    val perApp: Map<ObsiApp, AppTotals>,
    /** Highlights by their colour (0xAARRGGBB). */
    val colors: Map<Long, Int>,
    val mostHighlighted: List<TopHighlighted>,
    val bestDay: Pair<LocalDate, Int>?,
    val streak: Int,
    val bestStreak: Int,
    /** Seconds on each day, for the calendar. Not limited to the range. */
    val heat: Map<LocalDate, Int>,
    /** Days with a highlight or something finished, for the calendar too. */
    val doneDays: Set<LocalDate>,
) {
    val avgPerActiveDay: Int get() = if (activeDays == 0) 0 else totalSec / activeDays
    val isEmpty: Boolean get() = totalSec < 60 && highlights == 0 && finished == 0

    companion object {
        private const val MIN_SEC = 60

        /** Works out the page for [range] and the apps in [apps]. An empty [apps] means every app. Pure, so it is easy to test. */
        fun of(
            data: StatsData,
            range: StatsRange,
            apps: Set<ObsiApp> = emptySet(),
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): StatsView {
            val pick = { a: ObsiApp -> apps.isEmpty() || a in apps }
            val days: Map<ObsiApp, Map<LocalDate, DayActivity>> = data.days.filterKeys(pick).mapValues { (_, m) ->
                m.mapNotNull { (k, d) -> runCatching { LocalDate.parse(k) to d }.getOrNull() }.toMap()
            }
            val hl = data.highlights.filter { pick(it.app) }
            fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

            // The first day there is anything, for "all time".
            val first = (days.values.flatMap { it.keys } + hl.map { dayOf(it.created) }).minOrNull() ?: today
            val start = if (range == StatsRange.All) first else today.minusDays(range.days - 1)
            fun inRange(d: LocalDate) = !d.isBefore(start) && !d.isAfter(today)

            val totalSec = days.values.sumOf { m -> m.filterKeys(::inRange).values.sumOf { it.sec } }
            val previousSec = if (range == StatsRange.All) null else {
                val pStart = start.minusDays(range.days); val pEnd = start.minusDays(1)
                days.values.sumOf { m -> m.filterKeys { !it.isBefore(pStart) && !it.isAfter(pEnd) }.values.sumOf { it.sec } }
            }
            val hlIn = hl.filter { inRange(dayOf(it.created)) }

            // Seconds per day, all apps picked.
            val perDay = HashMap<LocalDate, Int>()
            days.values.forEach { m -> m.forEach { (d, a) -> perDay[d] = (perDay[d] ?: 0) + a.sec } }
            val done = HashSet<LocalDate>()
            days.values.forEach { m -> m.forEach { (d, a) -> if (a.finished > 0) done += d } }
            hl.forEach { done += dayOf(it.created) }
            val active = perDay.filterValues { it >= MIN_SEC }.keys + done

            // Streak, on the apps picked. An empty today keeps yesterday's streak.
            var at = if (today in active) today else today.minusDays(1)
            var streak = 0
            while (at in active) { streak++; at = at.minusDays(1) }
            var best = 0; var run = 0; var prev: LocalDate? = null
            for (d in active.sorted()) { run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1; best = maxOf(best, run); prev = d }

            fun sumApps(filter: (LocalDate) -> Boolean): Map<ObsiApp, Int> =
                days.mapValues { (_, m) -> m.filterKeys(filter).values.sumOf { it.sec } }.filterValues { it > 0 }

            val bars: List<StatsBar> = when (range) {
                StatsRange.Week, StatsRange.Month -> (0 until range.days.toInt()).map { i ->
                    val d = start.plusDays(i.toLong())
                    val label = if (range == StatsRange.Week) d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                    else if (d.dayOfMonth == 1 || i == 0 || (i % 7 == 0)) d.dayOfMonth.toString() else ""
                    StatsBar(label, sumApps { it == d }, d == today, d)
                }
                else -> {
                    val firstMonth = YearMonth.from(start)
                    val months = generateSequence(firstMonth) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(today)) }.toList()
                    if (months.size <= 24) months.map { m ->
                        StatsBar(m.month.getDisplayName(TextStyle.NARROW, Locale.getDefault()), sumApps { YearMonth.from(it) == m }, m == YearMonth.from(today), m.atDay(1))
                    } else months.map { it.year }.distinct().map { y ->
                        StatsBar(y.toString().takeLast(2).let { "'$it" }, sumApps { it.year == y }, y == today.year, LocalDate.of(y, 1, 1))
                    }
                }
            }

            val hours = MutableList(24) { 0 }
            days.values.forEach { m -> m.filterKeys(::inRange).values.forEach { d -> d.hours.forEach { (h, s) -> h.toIntOrNull()?.takeIf { it in 0..23 }?.let { hours[it] += s } } } }

            val top = days.flatMap { (a, m) -> m.filterKeys(::inRange).values.flatMap { d -> d.items.entries.map { Triple(a, it.key, it.value) } } }
                .groupBy { it.first to it.second }.map { (k, v) -> TopItem(k.second, k.first, v.sumOf { it.third }) }
                .sortedByDescending { it.sec }.take(5)

            val perApp = ObsiApp.entries.filter(pick).associateWith { a ->
                val m = days[a].orEmpty().filterKeys(::inRange).values
                AppTotals(m.sumOf { it.sec }, m.sumOf { it.finished }, hlIn.count { it.app == a })
            }.filterValues { it.sec > 0 || it.finished > 0 || it.highlights > 0 }

            val bestDay = perDay.filterKeys(::inRange).maxByOrNull { it.value }?.takeIf { it.value >= MIN_SEC }?.toPair()

            return StatsView(
                range = range,
                totalSec = totalSec,
                previousSec = previousSec,
                finished = days.values.sumOf { m -> m.filterKeys(::inRange).values.sumOf { it.finished } },
                highlights = hlIn.size,
                activeDays = active.count(::inRange),
                bars = bars,
                hours = hours,
                top = top,
                perApp = perApp,
                colors = hlIn.groupingBy { it.color }.eachCount(),
                mostHighlighted = hlIn.filter { it.itemTitle.isNotBlank() }.groupBy { it.app to it.itemTitle }
                    .map { (k, v) -> TopHighlighted(k.second, k.first, v.size) }.sortedByDescending { it.count }.take(5),
                bestDay = bestDay,
                streak = streak,
                bestStreak = maxOf(best, streak),
                heat = perDay,
                doneDays = done,
            )
        }
    }
}
