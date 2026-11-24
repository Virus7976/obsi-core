package app.obsi.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** One tab: its id, the name under the icon, the icon, and a small dot or count on it. */
class TabItem(val id: String, val label: String, val icon: ImageVector, val badge: String? = null)

/**
 * The tabs, as a floating bar at the bottom, the way Apple's own apps do it: an icon with a small name under
 * it, and a soft shape behind the chosen one that slides when you change tabs. [badge] "" is a dot.
 */
@Composable
fun FloatingTabs(tabs: List<TabItem>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val ids = tabs.map { it.id }
    val bounds = remember(ids) { mutableStateListOf(*Array(ids.size) { 0f to 0f }) }
    val index = ids.indexOf(selected).coerceAtLeast(0)
    val (x0, w0) = bounds.getOrElse(index) { 0f to 0f }
    val x by animateFloatAsState(x0, spring(0.75f, 450f), label = "tabX")
    val w by animateFloatAsState(w0, spring(0.75f, 450f), label = "tabW")
    Surface(
        modifier = modifier.fillMaxWidth().height(60.dp),
        shape = RoundedCornerShape(30.dp),
        color = scheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Box(Modifier.padding(5.dp)) {
            if (w > 0f) Box(
                Modifier.offset { IntOffset(x.toInt(), 0) }
                    .width(with(density) { w.toDp() }).fillMaxHeight()
                    .clip(RoundedCornerShape(25.dp)).background(scheme.surfaceContainerHighest),
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, t ->
                    val on = t.id == selected
                    val tone by animateColorAsState(if (on) scheme.primary else scheme.onSurfaceVariant, label = "tabTone")
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .onGloballyPositioned { c -> bounds[i] = c.positionInParent().x to c.size.width.toFloat() }
                            .clip(RoundedCornerShape(25.dp))
                            .clickable(role = Role.Tab, onClickLabel = t.label) { onSelect(t.id) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box {
                            Icon(t.icon, null, Modifier.size(24.dp), tint = tone)
                            val b = t.badge
                            if (b != null) {
                                if (b.isEmpty()) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).size(8.dp).clip(CircleShape).background(scheme.primary))
                                else Text(
                                    b, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-4).dp).clip(CircleShape).background(scheme.primary).padding(horizontal = 5.dp),
                                    style = MaterialTheme.typography.labelSmall, color = scheme.onPrimary,
                                )
                            }
                        }
                        Text(t.label, style = MaterialTheme.typography.labelSmall, color = tone, maxLines = 1)
                    }
                }
            }
        }
    }
}
