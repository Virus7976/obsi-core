package app.obsi.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsi.core.Brand
import app.obsi.core.ObsiApp
import app.obsi.core.link.Links
import app.obsi.core.share.SharedHighlight
import app.obsi.core.share.SharedHighlights

val ObsiApp.icon: ImageVector
    get() = when (this) {
        ObsiApp.Tube -> Icons.Rounded.SmartDisplay
        ObsiApp.Read -> Icons.AutoMirrored.Rounded.Article
        ObsiApp.Bible -> Icons.AutoMirrored.Rounded.MenuBook
    }

/** The other two Obsi apps, each one tap away. A missing app opens its download page. */
@Composable
fun ObsiAppsRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Brand.others.forEach { other ->
            val has = remember(other) { other.installed(context) }
            Surface(
                onClick = { Links.launch(context, other) },
                shape = RoundedCornerShape(16.dp),
                color = scheme.surfaceContainerHigh,
                modifier = Modifier.weight(1f),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                        Surface(color = scheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(other.icon, null, tint = scheme.onPrimaryContainer) }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(other.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (has) other.about else "Get it",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Loads the other apps' highlights once. Empty while it loads, and when none are there. */
@Composable
fun rememberOtherHighlights(): List<SharedHighlight> {
    val context = LocalContext.current
    var rows by remember { mutableStateOf(emptyList<SharedHighlight>()) }
    LaunchedEffect(Unit) { rows = SharedHighlights.fromOthers(context).sortedByDescending { it.created } }
    return rows
}

/** Keeps the highlights whose words, note, title or tags hold every word of [query]. */
fun List<SharedHighlight>.matching(query: String): List<SharedHighlight> {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return this
    return filter { h ->
        val hay = (h.text + " " + h.note + " " + h.itemTitle + " " + h.place + " " + h.tags.joinToString(" ")).lowercase()
        words.all { it in hay }
    }
}

/** One highlight from another Obsi app. A tap opens it there. */
@Composable
fun OtherHighlightRow(h: SharedHighlight, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clickable { Links.openIn(context, h.app, h.itemId, h.at) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape)) {
            Surface(color = Color(h.color), modifier = Modifier.size(10.dp)) {}
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.text, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (h.note.isNotBlank()) Text(h.note, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(h.app.icon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    listOf(h.app.title, h.itemTitle, h.place).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The other apps' highlights as list rows, one group per app, each under its own name. */
fun LazyListScope.otherHighlights(rows: List<SharedHighlight>, sides: androidx.compose.ui.unit.Dp = 0.dp) {
    rows.groupBy { it.app }.forEach { (from, list) ->
        item("obsi-head-" + from.name) {
            val scheme = MaterialTheme.colorScheme
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, start = sides + 4.dp, end = sides + 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(from.icon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("From ${from.title}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("${list.size}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
        }
        itemsIndexed(list, key = { i, _ -> "obsi-" + from.name + "-" + i }) { _, h ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.padding(horizontal = sides, vertical = 4.dp)) { OtherHighlightRow(h) }
        }
    }
}

/** "Your Obsi apps" with the switcher under it. For the top of Settings. */
@Composable
fun ObsiAppsSection(modifier: Modifier = Modifier, sides: androidx.compose.ui.unit.Dp = 16.dp) {
    Column(modifier.fillMaxWidth().padding(horizontal = sides, vertical = 8.dp)) {
        Text("Your Obsi apps", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        ObsiAppsRow()
    }
}

/**
 * The other two apps and your newest highlights in them. For a Home screen that is a plain
 * column. Shows only the switcher when there are no highlights yet.
 */
@Composable
fun FromOtherApps(modifier: Modifier = Modifier, limit: Int = 3) {
    val rows = rememberOtherHighlights()
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Text("Your other apps", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.padding(top = 4.dp))
        Text(
            if (rows.isEmpty()) "Open " + Brand.others.joinToString(" or ") { it.title } + " in one tap."
            else "Your newest highlights in " + Brand.others.joinToString(" and ") { it.title } + ".",
            color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.padding(top = 12.dp))
        ObsiAppsRow()
        rows.take(limit).forEach { h ->
            Spacer(Modifier.padding(top = 10.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = scheme.surfaceContainer) { OtherHighlightRow(h) }
        }
    }
}

/**
 * "Set up like ObsiRead": shown while this app has no vault folder and another Obsi app has one.
 * One tap copies the vault and tags, and opens the folder picker right at that vault's folder.
 */
@Composable
fun SameSetupCard(needed: Boolean, onApply: (app.obsi.core.share.ObsiSetup, String) -> Unit, modifier: Modifier = Modifier) {
    if (!needed) return
    val context = LocalContext.current
    var setups by remember { mutableStateOf(emptyList<app.obsi.core.share.ObsiSetup>()) }
    LaunchedEffect(Unit) { setups = app.obsi.core.share.SharedSetup.fromOthers(context) }
    var picking by remember { mutableStateOf<app.obsi.core.share.ObsiSetup?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        val setup = picking ?: return@rememberLauncherForActivityResult
        picking = null
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        onApply(setup, uri.toString())
        android.widget.Toast.makeText(context, "Linked ${setup.vault.ifBlank { "your vault" }}.", android.widget.Toast.LENGTH_SHORT).show()
    }
    val setup = setups.firstOrNull() ?: return
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = scheme.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Set up like ${setup.app.title}", style = MaterialTheme.typography.titleMedium, color = scheme.onSecondaryContainer)
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                "Use the vault “${setup.vault.ifBlank { "your vault" }}” and your tags. Android asks you to pick the folder once. It opens right there.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSecondaryContainer,
            )
            Spacer(Modifier.padding(top = 10.dp))
            androidx.compose.material3.Button(onClick = { picking = setup; picker.launch(android.net.Uri.parse(setup.folder)) }) {
                Text("Use the same vault")
            }
        }
    }
}
