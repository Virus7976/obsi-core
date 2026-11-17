package app.obsi.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.obsi.core.Brand
import app.obsi.core.ObsiApp
import app.obsi.core.data.Activity
import app.obsi.core.data.Stats
import app.obsi.core.data.StatsData
import app.obsi.core.data.StatsRange
import app.obsi.core.data.StatsView
import app.obsi.core.data.StatsBar
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import java.time.LocalDate
import kotlinx.coroutines.delay
import java.time.format.TextStyle
import java.util.Locale

/** Each app's colour in the shared stats. */
fun ObsiApp.statColor(): Color = when (this) {
    ObsiApp.Tube -> Color(0xFFE5484D)
    ObsiApp.Read -> Color(0xFFF2A33A)
    ObsiApp.Bible -> Color(0xFFB98BFF)
    ObsiApp.Learn -> Color(0xFF3FB68B)
}

/**
 * Counts time spent while [active] is true and the app is on screen. Put it on the screen where people
 * read or watch. For a player that keeps going in the background, count from the player instead.
 */
@Composable
fun CountTime(active: Boolean = true, item: String = "") {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var resumed by remember { androidx.compose.runtime.mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(active, resumed, item) {
        if (!active || !resumed) return@LaunchedEffect
        val log = Activity.log(ctx)
        while (true) { delay(15_000); log.addTime(15, item) }
    }
}

/** The stats of every Obsi app on the phone. Read again each time the screen opens. */
@Composable
fun rememberStats(): Stats? {
    val ctx = LocalContext.current
    val s by produceState<Stats?>(null) { value = runCatching { Activity.load(ctx) }.getOrNull() }
    return s
}

/** A small card for Home: the streak and this week's time. Opens the stats page. Hidden until there is something to show. */
@Composable
fun StatsCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val stats = rememberStats() ?: return
    if (stats.isEmpty) return
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onOpen, shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainer, modifier = modifier) {
        Row(Modifier.padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocalFireDepartment, null, tint = if (stats.streak > 0) Color(0xFFFF8A3D) else scheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (stats.streak > 0) "${stats.streak}-day streak" else "Stats", style = MaterialTheme.typography.titleSmall)
                Text("${Activity.duration(stats.weekSec)} this week", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            MiniWeek(stats, Modifier.width(84.dp).height(28.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MiniWeek(stats: Stats, modifier: Modifier) {
    val max = stats.week.maxOf { it.second.values.sum() }.coerceAtLeast(1)
    Row(modifier.padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        stats.week.forEach { (_, m) ->
            val f = m.values.sum().toFloat() / max
            Box(
                Modifier.weight(1f).fillMaxHeight(f.coerceAtLeast(0.12f)).clip(RoundedCornerShape(3.dp))
                    .background(if (f > 0f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}


/** The full stats page, read again each time it opens. */
@Composable
private fun rememberStatsData(): StatsData? {
    val ctx = LocalContext.current
    val s by produceState<StatsData?>(null) { value = runCatching { Activity.raw(ctx) }.getOrNull() }
    return s
}

/**
 * Your stats across every Obsi app. Pick a range (week, month, year, all time) and the apps to count.
 * It shows time spent and how it compares, a chart, the streak with a calendar, the hours you learn,
 * what you spent most time on, your highlights, and records.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val data = rememberStatsData()
    val scheme = MaterialTheme.colorScheme
    var range by rememberSaveable { androidx.compose.runtime.mutableStateOf(StatsRange.Week) }
    var picked by rememberSaveable { androidx.compose.runtime.mutableStateOf(listOf<String>()) }
    val apps = picked.mapNotNull { n -> ObsiApp.entries.firstOrNull { it.name == n } }.toSet()
    val view = remember(data, range, picked) { data?.let { StatsView.of(it, range, apps) } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackRow("Stats", onBack, subtitle = "Every Obsi app on this phone")
        if (data == null || view == null) return@Column
        val withData = data.days.filterValues { m -> m.values.any { it.sec > 0 || it.finished > 0 } }.keys + data.highlights.map { it.app }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            // The range.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                StatsRange.entries.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = range == r, onClick = { range = r },
                        shape = SegmentedButtonDefaults.itemShape(i, StatsRange.entries.size),
                    ) { Text(r.label, maxLines = 1) }
                }
            }
            // The apps. None picked counts them all.
            if (withData.size > 1) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = apps.isEmpty(), onClick = { picked = emptyList() }, label = { Text("All apps") })
                ObsiApp.entries.filter { it in withData }.forEach { a ->
                    FilterChip(
                        selected = a in apps,
                        onClick = { picked = if (a.name in picked) picked - a.name else picked + a.name },
                        label = { Text(a.title) },
                        leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(a.statColor())) },
                    )
                }
            }

            // Only when there is nothing at all. A quiet week still shows its numbers, so it can be compared.
            val nothing = remember(data, picked) { StatsView.of(data, StatsRange.All, apps).isEmpty }
            if (nothing) {
                Text(
                    "Nothing yet. Watch, read or highlight something, and your days show up here.",
                    Modifier.padding(vertical = 32.dp, horizontal = 4.dp), color = scheme.onSurfaceVariant,
                )
            }

            Hero(view)
            Card("Time") { Chart(view) }
            Card("Streak") { StreakAndCalendar(view) }
            if (view.hours.any { it > 0 }) Card("When you learn") { Hours(view.hours) }
            if (view.top.isNotEmpty()) Card("Most time on") { TopList(view.top.map { Triple(it.app, it.title, it.sec) }, isTime = true) }
            if (view.highlights > 0) Card("Highlights") { HighlightsPart(view) }
            if (view.perApp.size > 1) Card("By app") { ByApp(view) }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** The total for the range, big, with how it compares to the time just before, and three small numbers. */
@Composable
private fun Hero(v: StatsView) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, start = 4.dp, end = 4.dp)) {
        Text(
            when (v.range) { StatsRange.Week -> "Last 7 days"; StatsRange.Month -> "Last 30 days"; StatsRange.Year -> "Last 12 months"; StatsRange.All -> "Since you started" },
            style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Activity.duration(v.totalSec), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
            v.previousSec?.let { p ->
                Spacer(Modifier.width(12.dp))
                val change = when {
                    p < 60 && v.totalSec < 60 -> null
                    p < 60 -> "New"
                    else -> ((v.totalSec - p) * 100 / p).let { if (it >= 0) "▲ $it%" else "▼ ${-it}%" }
                }
                if (change != null) Surface(
                    shape = RoundedCornerShape(50),
                    color = if (v.totalSec >= p) scheme.primaryContainer else scheme.surfaceContainerHighest,
                    modifier = Modifier.padding(bottom = 10.dp),
                ) {
                    Text(
                        change, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge,
                        color = if (v.totalSec >= p) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                    )
                }
            }
        }
        v.previousSec?.let { p ->
            Text(
                "${Activity.duration(p)} the ${v.range.label.lowercase()} before",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Finished", v.finished.toString(), Modifier.weight(1f))
            Tile("Highlights", v.highlights.toString(), Modifier.weight(1f))
            Tile("Days active", v.activeDays.toString(), Modifier.weight(1f))
        }
    }
}

/** Stacked bars, one colour per app. Tap a bar to see its time. */
@Composable
private fun Chart(v: StatsView) {
    val scheme = MaterialTheme.colorScheme
    var picked by remember(v) { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    val max = v.bars.maxOfOrNull { it.total }?.coerceAtLeast(60) ?: 60
    val shown = picked?.let { v.bars.getOrNull(it) }
    Text(
        if (shown != null) "${barName(v.range, shown)}  ·  ${Activity.duration(shown.total)}"
        else "Average ${Activity.duration(if (v.bars.isEmpty()) 0 else v.totalSec / v.bars.size)} a ${barUnit(v)}. Tap a bar for its time.",
        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    val gap = if (v.bars.size > 14) 2.dp else 8.dp
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
        v.bars.forEachIndexed { i, b ->
            val grow by animateFloatAsState(1f, tween(450 + i * 12), label = "bar")
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                    picked = if (picked == i) null else i
                },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom,
            ) {
                val alpha = if (picked == null || picked == i) 1f else 0.35f
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false)
                        .fillMaxHeight((b.total.toFloat() / max * grow).coerceIn(0.02f, 1f))
                        .clip(RoundedCornerShape(if (v.bars.size > 14) 3.dp else 6.dp))
                        .background(scheme.outlineVariant.copy(alpha = 0.35f)),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    b.perApp.entries.sortedBy { it.key.ordinal }.forEach { (a, sec) ->
                        Box(Modifier.fillMaxWidth().weight(sec.toFloat().coerceAtLeast(1f)).background(a.statColor().copy(alpha = alpha)))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    b.label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = if (b.now) scheme.onSurface else scheme.onSurfaceVariant,
                    fontWeight = if (b.now) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
    val inChart = v.bars.flatMap { it.perApp.keys }.distinct().sortedBy { it.ordinal }
    if (inChart.size > 1) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        inChart.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(a.statColor()))
                Spacer(Modifier.width(6.dp))
                Text(a.title, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

private fun barUnit(v: StatsView) = when (v.range) {
    StatsRange.Week, StatsRange.Month -> "day"
    else -> if (v.bars.firstOrNull()?.label?.startsWith("'") == true) "year" else "month"
}

private fun barName(r: StatsRange, b: StatsBar): String = when (r) {
    StatsRange.Week, StatsRange.Month -> b.day.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
    else -> if (b.label.startsWith("'")) b.day.year.toString() else b.day.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))
}

/** The streak, then a calendar of the last 20 weeks: the darker a day, the more time. Tap a day to see it. */
@Composable
private fun StreakAndCalendar(v: StatsView) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0x33FF8A3D)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(28.dp), tint = Color(0xFFFF8A3D))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (v.streak == 1) "1 day" else "${v.streak} days", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                if (v.streak == 0) "Do a minute today to start a streak." else "in a row. Best: ${v.bestStreak} days.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    val today = LocalDate.now()
    val weeks = 20
    // Columns are weeks, Monday at the top. The last column is this week.
    val lastMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val firstMonday = lastMonday.minusWeeks((weeks - 1).toLong())
    val values = v.heat.filterKeys { !it.isBefore(firstMonday) }.values.filter { it > 0 }.sorted()
    fun level(sec: Int): Int {
        if (sec <= 0 || values.isEmpty()) return 0
        val rank = values.indexOfFirst { it >= sec }.let { if (it < 0) values.size else it }.toFloat() / values.size
        return when { rank < 0.25f -> 1; rank < 0.5f -> 2; rank < 0.8f -> 3; else -> 4 }
    }
    var tapped by remember { androidx.compose.runtime.mutableStateOf<LocalDate?>(null) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (w in 0 until weeks) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (d in 0 until 7) {
                    val day = firstMonday.plusWeeks(w.toLong()).plusDays(d.toLong())
                    val future = day.isAfter(today)
                    val sec = v.heat[day] ?: 0
                    val l = level(sec).let { if (it == 0 && day in v.doneDays) 1 else it }
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(3.dp))
                            .background(
                                when {
                                    future -> Color.Transparent
                                    l == 0 -> scheme.outlineVariant.copy(alpha = 0.35f)
                                    else -> scheme.primary.copy(alpha = 0.25f + 0.1875f * l)
                                },
                            )
                            .then(if (day == tapped) Modifier.border(1.5.dp, scheme.onSurface, RoundedCornerShape(3.dp)) else Modifier)
                            .clickable(enabled = !future) { tapped = if (tapped == day) null else day },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            tapped?.let { d -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")) + "  ·  " + Activity.duration(v.heat[d] ?: 0) }
                ?: "Last 20 weeks",
            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        Text("Less ", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        (0..4).forEach { l ->
            Box(
                Modifier.padding(horizontal = 1.dp).size(10.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (l == 0) scheme.outlineVariant.copy(alpha = 0.35f) else scheme.primary.copy(alpha = 0.25f + 0.1875f * l)),
            )
        }
        Text(" More", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
}

/** Time by hour of the day, with the busiest hour in words. */
@Composable
private fun Hours(hours: List<Int>) {
    val scheme = MaterialTheme.colorScheme
    val max = hours.max().coerceAtLeast(1)
    val top = hours.indices.maxBy { hours[it] }
    fun name(h: Int) = when { h == 0 -> "midnight"; h == 12 -> "noon"; h < 12 -> "$h am"; else -> "${h - 12} pm" }
    val part = when (top) { in 5..11 -> "in the morning"; in 12..16 -> "in the afternoon"; in 17..21 -> "in the evening"; else -> "at night" }
    Text("Most $part, around ${name(top)}.", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        hours.forEachIndexed { h, s ->
            Box(
                Modifier.weight(1f).fillMaxHeight((s.toFloat() / max).coerceIn(0.04f, 1f)).clip(RoundedCornerShape(2.dp))
                    .background(if (h == top) scheme.primary else scheme.primary.copy(alpha = 0.35f)),
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        listOf("12a", "6a", "12p", "6p").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
    }
}

/** A short ranked list with a bar for each row: time spent, or highlights made. */
@Composable
private fun TopList(rows: List<Triple<ObsiApp, String, Int>>, isTime: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val max = rows.maxOfOrNull { it.third }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { (a, title, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(a.icon, a.title, Modifier.size(18.dp), tint = a.statColor())
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isTime) Activity.duration(n) else n.toString(), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(scheme.outlineVariant.copy(alpha = 0.35f))) {
                        Box(Modifier.fillMaxWidth(n.toFloat() / max).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(a.statColor()))
                    }
                }
            }
        }
    }
}

/** How many highlights, a strip of their colours, and where you highlighted most. */
@Composable
private fun HighlightsPart(v: StatsView) {
    val scheme = MaterialTheme.colorScheme
    Text(if (v.highlights == 1) "1 highlight" else "${v.highlights} highlights", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
        v.colors.entries.sortedByDescending { it.value }.forEach { (c, n) -> Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(Color(c))) }
    }
    if (v.mostHighlighted.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        Text("Most highlighted", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        TopList(v.mostHighlighted.map { Triple(it.app, it.title, it.count) }, isTime = false)
    }
}

/** Each app's share of the time, with what it adds. */
@Composable
private fun ByApp(v: StatsView) {
    val scheme = MaterialTheme.colorScheme
    val total = v.perApp.values.sumOf { it.sec }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        v.perApp.entries.sortedByDescending { it.value.sec }.forEach { (a, t) ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(a.icon, null, Modifier.size(18.dp), tint = a.statColor())
                    Spacer(Modifier.width(10.dp))
                    Text(a.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text("${t.sec * 100 / total}%", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(scheme.outlineVariant.copy(alpha = 0.35f))) {
                    Box(Modifier.fillMaxWidth(t.sec.toFloat() / total).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(a.statColor()))
                }
                Text(
                    listOf(
                        Activity.duration(t.sec),
                        if (t.finished == 1) "1 ${a.noun} finished" else "${t.finished} ${a.nouns} finished",
                        if (t.highlights == 1) "1 highlight" else "${t.highlights} highlights",
                    ).joinToString("  ·  "),
                    Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
        }
    }
    v.bestDay?.let { (d, s) ->
        Spacer(Modifier.height(16.dp))
        Text(
            "Your biggest day: ${d.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))}, ${Activity.duration(s)}.",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
