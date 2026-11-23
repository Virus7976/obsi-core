package app.obsi.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One quick button at the top of a ⋮ menu. [key] is what the Quick buttons page stores. */
class QuickAction(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val on: Boolean = false,
    val enabled: Boolean = true,
    val run: () -> Unit,
)

/** A group that opens inside the menu, with a back arrow, so the first page stays short. */
class MenuPage(val key: String, val title: String, val content: @Composable ColumnScope.() -> Unit)

/** The most quick buttons that fit in one row. */
const val MAX_QUICK = 4

/**
 * The ⋮ menu every Obsi app uses on a reading screen. The top has up to four quick buttons you pick yourself.
 * Under them, [main] adds the rows; a row made with [MenuGroupRow] opens one of [pages] in the same menu.
 * The last row opens the Quick buttons page. [picked] is the keys you chose, in order; [onPicked] saves them.
 */
@Composable
fun ColumnScope.ActionMenu(
    quick: List<QuickAction>,
    picked: List<String>,
    onPicked: (List<String>) -> Unit,
    pages: List<MenuPage> = emptyList(),
    main: @Composable ColumnScope.(open: (String) -> Unit) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var page by remember { mutableStateOf<String?>(null) }
    val customise = "quick-buttons"
    val shownPage = pages.firstOrNull { it.key == page }
    if (page != null) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(shownPage?.title ?: "Quick buttons", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(Modifier.padding(bottom = 4.dp), color = scheme.outlineVariant.copy(alpha = 0.4f))
    }
    when {
        page == null -> {
            val shown = picked.mapNotNull { k -> quick.firstOrNull { it.key == k } }.take(MAX_QUICK)
            if (shown.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { q -> MenuTile(q.label, q.icon, q.on, q.enabled) { q.run() } }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant.copy(alpha = 0.4f))
            }
            main { page = it }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant.copy(alpha = 0.4f))
            MenuGroupRow("Quick buttons", Icons.Rounded.Tune, "Pick the buttons at the top") { page = customise }
        }
        page == customise -> {
            Text(
                "Up to $MAX_QUICK. They show in the order you tick them.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).width(260.dp),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
            quick.forEach { q ->
                val on = q.key in picked
                val full = !on && picked.count { k -> quick.any { it.key == k } } >= MAX_QUICK
                DropdownMenuItem(
                    text = { Text(q.label) },
                    leadingIcon = { Icon(q.icon, null) },
                    trailingIcon = { Checkbox(on, null, enabled = !full) },
                    enabled = !full,
                    onClick = { onPicked(if (on) picked - q.key else picked + q.key) },
                )
            }
        }
        shownPage != null -> shownPage.content(this)
        else -> androidx.compose.runtime.LaunchedEffect(page) { page = null }
    }
}

/** A row that opens a group: its name, what is in it now, and a › at the end. */
@Composable
fun MenuGroupRow(title: String, icon: ImageVector, now: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title)
                if (now.isNotBlank()) Text(now, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
        onClick = onClick,
    )
}

/** An action with one line under it that says what it does. */
@Composable
fun MenuActionRow(title: String, about: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column(Modifier.width(220.dp)) {
                Text(title)
                Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick,
    )
}

/** A square quick button: an icon over a short name, filled while it is on. */
@Composable
fun MenuTile(label: String, icon: ImageVector, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.size(width = 68.dp, height = 64.dp).clip(RoundedCornerShape(16.dp))
            .background(if (on) scheme.primaryContainer else scheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val tint = when {
            !enabled -> scheme.outline
            on -> scheme.onPrimaryContainer
            else -> scheme.onSurface
        }
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}
