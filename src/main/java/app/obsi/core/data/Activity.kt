package app.obsi.core.data

import android.content.Context
import app.obsi.core.Brand
import app.obsi.core.ObsiApp
import app.obsi.core.share.SharedHighlight
import app.obsi.core.share.SharedHighlights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What you did in one app on one day. */
@Serializable
data class DayActivity(
    /** Seconds spent watching, listening or reading. */
    val sec: Int = 0,
    /** Videos, articles, lessons or chapters finished. */
    val finished: Int = 0,
)

@Serializable
data class ActivityData(
    /** Keyed by day, "2026-11-16". */
    val days: Map<String, DayActivity> = emptyMap(),
)

/** This app's own record of time spent and things finished. The other Obsi apps read it to show shared stats. */
class ActivityLog(context: Context) : JsonFileStore<ActivityData>(File(context.filesDir, "activity.json")) {
    private val _state = MutableStateFlow(readOrNull() ?: ActivityData())
    val state: StateFlow<ActivityData> = _state.asStateFlow()

    fun addTime(sec: Int) = change { it.copy(sec = it.sec + sec) }
    fun finished() = change { it.copy(finished = it.finished + 1) }

    private fun change(block: (DayActivity) -> DayActivity) {
        val day = Activity.today()
        _state.update { d -> d.copy(days = d.days + (day to block(d.days[day] ?: DayActivity()))) }
        persist(_state.value)
    }

    override fun encode(value: ActivityData) = storeJson.encodeToString(ActivityData.serializer(), value)
    override fun decode(text: String) = storeJson.decodeFromString(ActivityData.serializer(), text)
}

/** The stats of every Obsi app on this phone, put together. */
data class Stats(
    /** Days in a row with something done, up to today. Today still counts as kept until it ends. */
    val streak: Int,
    val bestStreak: Int,
    /** The last seven days, oldest first: per app, seconds spent. */
    val week: List<Pair<LocalDate, Map<ObsiApp, Int>>>,
    val weekSec: Int,
    /** Per app, everything so far. */
    val perApp: Map<ObsiApp, AppTotals>,
    /** Highlights made on each of the last seven days, oldest first. */
    val weekHighlights: List<Int>,
) {
    val totalSec: Int get() = perApp.values.sumOf { it.sec }
    val totalHighlights: Int get() = perApp.values.sumOf { it.highlights }
    val totalFinished: Int get() = perApp.values.sumOf { it.finished }
    val isEmpty: Boolean get() = totalSec < 60 && totalHighlights == 0 && totalFinished == 0
}

data class AppTotals(val sec: Int, val finished: Int, val highlights: Int)

object Activity {
    @Volatile
    private var log: ActivityLog? = null

    /** This app's log. The share door can be asked before the app has started, so it opens here, once. */
    fun log(context: Context): ActivityLog = log ?: synchronized(this) {
        log ?: ActivityLog(context.applicationContext).also { log = it }
    }

    fun today(): String = LocalDate.now().toString()

    /** A day counts toward the streak with a minute spent, a highlight, or something finished. */
    private const val MIN_SEC = 60

    /** Puts every app's days and highlights together. Pure, so it is easy to test. */
    fun build(
        days: Map<ObsiApp, Map<String, DayActivity>>,
        highlights: List<SharedHighlight>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Stats {
        val hlDays = highlights.groupingBy { Instant.ofEpochMilli(it.created).atZone(zone).toLocalDate() }.eachCount()
        val active = HashSet<LocalDate>()
        days.values.forEach { m -> m.forEach { (k, d) -> if (d.sec >= MIN_SEC || d.finished > 0) runCatching { active += LocalDate.parse(k) } } }
        active += hlDays.keys

        // Today is not over: an empty today keeps yesterday's streak alive.
        var day = if (today in active) today else today.minusDays(1)
        var streak = 0
        while (day in active) { streak++; day = day.minusDays(1) }
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in active.sorted()) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            best = maxOf(best, run); prev = d
        }

        val week = (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            d to days.mapValues { (_, m) -> m[d.toString()]?.sec ?: 0 }.filterValues { it > 0 }
        }
        val perApp = ObsiApp.entries.associateWith { a ->
            val m = days[a].orEmpty().values
            AppTotals(m.sumOf { it.sec }, m.sumOf { it.finished }, highlights.count { it.app == a })
        }.filterValues { it.sec > 0 || it.finished > 0 || it.highlights > 0 }
        return Stats(
            streak = streak,
            bestStreak = maxOf(best, streak),
            week = week,
            weekSec = week.sumOf { it.second.values.sum() },
            perApp = perApp,
            weekHighlights = week.map { hlDays[it.first] ?: 0 },
        )
    }

    /** Reads this app and every other Obsi app on the phone. An app that is missing or old adds nothing. */
    suspend fun load(context: Context): Stats = withContext(Dispatchers.IO) {
        val days = HashMap<ObsiApp, Map<String, DayActivity>>()
        days[Brand.app] = log(context).state.value.days
        Brand.others.filter { it.installed(context) }.forEach { other -> days[other] = SharedActivity.read(context, other) }
        val own = runCatching { SharedHighlights.source?.invoke() }.getOrNull().orEmpty().map { it.copy(app = Brand.app) }
        val highlights = own + SharedHighlights.fromOthers(context)
        build(days, highlights.filter { it.text.isNotBlank() || it.note.isNotBlank() })
    }

    /** "2h 05m", "12m", "under a minute". */
    fun duration(sec: Int): String {
        val m = sec / 60
        return when {
            m < 1 -> "0m"
            m < 60 -> "${m}m"
            else -> "${m / 60}h ${(m % 60).toString().padStart(2, '0')}m"
        }
    }
}

/** Reads another Obsi app's days through its share door. */
object SharedActivity {
    private val columns = arrayOf("day", "sec", "finished")

    internal fun cursor(context: Context): android.database.Cursor {
        val days = Activity.log(context).state.value.days
        return android.database.MatrixCursor(columns, days.size).apply {
            days.forEach { (k, d) -> addRow(arrayOf<Any>(k, d.sec, d.finished)) }
        }
    }

    fun read(context: Context, from: ObsiApp): Map<String, DayActivity> = runCatching {
        val uri = android.net.Uri.parse("content://${SharedHighlights.authority(from)}/activity")
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val out = HashMap<String, DayActivity>(c.count)
            while (c.moveToNext()) {
                out[c.getString(c.getColumnIndexOrThrow("day"))] =
                    DayActivity(c.getInt(c.getColumnIndexOrThrow("sec")), c.getInt(c.getColumnIndexOrThrow("finished")))
            }
            out
        }.orEmpty()
    }.getOrDefault(emptyMap())
}
