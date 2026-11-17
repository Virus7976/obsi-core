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
fun CountTime(active: Boolean = true) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var resumed by remember { androidx.compose.runtime.mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(active, resumed) {
        if (!active || !resumed) return@LaunchedEffect
        val log = Activity.log(ctx)
        while (true) { delay(15_000); log.addTime(15) }
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

/** Your stats across every Obsi app: the streak, the last seven days, and what each app adds. */
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val stats = rememberStats()
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackRow("Stats", onBack, subtitle = "Every Obsi app on this phone")
        if (stats == null) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            if (stats.isEmpty) {
                Text(
                    "Nothing yet. Watch, read or highlight something, and your days show up here.",
                    Modifier.padding(vertical = 32.dp), color = scheme.onSurfaceVariant,
                )
                return@Column
            }
            // The streak, big.
            Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0x33FF8A3D)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(32.dp), tint = Color(0xFFFF8A3D))
                    }
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text(
                            if (stats.streak == 1) "1 day" else "${stats.streak} days",
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (stats.streak == 0) "Do a minute today to start a streak." else "in a row. Best: ${stats.bestStreak} days.",
                            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // The week.
            Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("This week", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(Activity.duration(stats.weekSec), style = MaterialTheme.typography.titleMedium, color = scheme.primary)
                    }
                    Spacer(Modifier.height(16.dp))
                    WeekBars(stats)
                    val apps = stats.week.flatMap { it.second.keys }.distinct()
                    if (apps.size > 1) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        apps.forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(a.statColor()))
                                Spacer(Modifier.width(6.dp))
                                Text(a.title, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                    val hl = stats.weekHighlights.sum()
                    if (hl > 0) Text(
                        if (hl == 1) "1 highlight this week" else "$hl highlights this week",
                        Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
            }

            // Totals.
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("Time", Activity.duration(stats.totalSec), Modifier.weight(1f))
                Tile("Finished", stats.totalFinished.toString(), Modifier.weight(1f))
                Tile("Highlights", stats.totalHighlights.toString(), Modifier.weight(1f))
            }

            // Each app.
            if (stats.perApp.size > 1 || stats.perApp.keys.firstOrNull() != Brand.app) {
                Text("By app", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp, start = 4.dp))
                stats.perApp.forEach { (a, t) ->
                    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(a.statColor()))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOf(
                                        Activity.duration(t.sec),
                                        if (t.finished == 1) "1 ${a.noun} finished" else "${t.finished} ${a.nouns} finished",
                                        if (t.highlights == 1) "1 highlight" else "${t.highlights} highlights",
                                    ).joinToString("  ·  "),
                                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WeekBars(stats: Stats) {
    val scheme = MaterialTheme.colorScheme
    val max = stats.week.maxOf { it.second.values.sum() }.coerceAtLeast(60)
    Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        stats.week.forEachIndexed { i, (day, m) ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                val total = m.values.sum()
                val grow by animateFloatAsState(1f, tween(500 + i * 40), label = "bar")
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false)
                        .fillMaxHeight((total.toFloat() / max * grow).coerceIn(0.03f, 1f))
                        .clip(RoundedCornerShape(6.dp)).background(scheme.outlineVariant.copy(alpha = 0.4f)),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    m.entries.sortedBy { it.key.ordinal }.forEach { (a, sec) ->
                        Box(Modifier.fillMaxWidth().weight(sec.toFloat().coerceAtLeast(1f)).background(a.statColor()))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == stats.week.lastIndex) scheme.onSurface else scheme.onSurfaceVariant,
                    fontWeight = if (i == stats.week.lastIndex) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
