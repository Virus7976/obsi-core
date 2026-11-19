package app.obsi.core.ui

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.obsi.core.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The release notes of this app, from docs/releases in its repo. Each app's build copies them into
 * its assets under "releases/", so they show with no network.
 */
object ReleaseNotes {
    data class Note(val version: String, val title: String, val body: String)

    fun all(context: Context): List<Note> = runCatching {
        context.assets.list("releases").orEmpty().filter { it.endsWith(".md") }.mapNotNull { name ->
            val text = context.assets.open("releases/$name").bufferedReader().use { it.readText() }
            parse(name.removeSuffix(".md").removePrefix("v"), text)
        }.sortedWith { a, b -> compare(b.version, a.version) }
    }.getOrDefault(emptyList())

    fun parse(version: String, text: String): Note {
        val lines = text.replace("\r", "").lines()
        val first = lines.firstOrNull { it.isNotBlank() }.orEmpty()
        val title = if (first.startsWith("# ")) first.removePrefix("# ").trim() else "${Brand.name} $version"
        val body = (if (first.startsWith("# ")) lines.dropWhile { it.isBlank() }.drop(1) else lines).joinToString("\n").trim()
        return Note(version, title, body)
    }

    /** "2.10.0" after "2.9.3". */
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    private fun prefs(context: Context) = context.getSharedPreferences("obsi_whats_new", Context.MODE_PRIVATE)

    /** The notes you have not seen since the last update. Empty on a new install and when there is nothing new. */
    fun unseen(context: Context): List<Note> {
        val now = installedVersion(context)
        val p = prefs(context)
        val seen = p.getString("seen", null)
        if (seen == null) { p.edit().putString("seen", now).apply(); return emptyList() }
        if (compare(now, seen) <= 0) return emptyList()
        return all(context).filter { compare(it.version, seen) > 0 && compare(it.version, now) <= 0 }
    }

    fun markSeen(context: Context) { prefs(context).edit().putString("seen", installedVersion(context)).apply() }
}

/** Every version of this app and what changed in it. The newest is open. */
@Composable
fun WhatsNewScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val notes by produceState(emptyList<ReleaseNotes.Note>()) { value = withContext(Dispatchers.IO) { ReleaseNotes.all(ctx) } }
    val current = remember { ReleaseNotes.installedVersion(ctx) }
    androidx.activity.compose.BackHandler(onBack = onBack)
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { BackRow("What's new", onBack, subtitle = if (current.isNotBlank()) "${Brand.name} $current on this phone" else null) }
        if (notes.isEmpty()) item {
            Text("No release notes in this build.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(notes, key = { it.version }) { n ->
            NoteCard(n, installed = n.version == current, startOpen = n == notes.firstOrNull())
        }
    }
}

@Composable
private fun NoteCard(n: ReleaseNotes.Note, installed: Boolean, startOpen: Boolean) {
    var open by remember { mutableStateOf(startOpen) }
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp))
            .background(scheme.surfaceContainer).animateContentSize(),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(if (installed) scheme.primary else scheme.secondaryContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(n.version, style = MaterialTheme.typography.labelLarge, color = if (installed) scheme.onPrimary else scheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(firstLine(n.body), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(if (installed) "On this phone" else null, changes(n.body)).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1,
                )
            }
            Icon(Icons.Rounded.ExpandMore, if (open) "Close" else "Open", Modifier.rotate(if (open) 180f else 0f), tint = scheme.onSurfaceVariant)
        }
        if (open) MarkdownText(n.body, Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp))
    }
}

/** The first change, by its bold lead when it has one. */
private fun firstLine(body: String): String {
    val l = body.lines().map { it.trim() }.firstOrNull { it.startsWith("- ") || (it.isNotBlank() && !it.startsWith("#")) }.orEmpty().removePrefix("- ")
    val lead = if (l.startsWith("**") && l.indexOf("**", 2) > 2) l.substring(2, l.indexOf("**", 2)) else l.replace("**", "")
    return lead.trim().trimEnd('.').take(80)
}

/** "4 changes". */
private fun changes(body: String): String? {
    val n = body.lines().count { it.trim().startsWith("- ") }
    return if (n == 0) null else if (n == 1) "1 change" else "$n changes"
}

/**
 * Put once at the root of the app. After an update it shows what changed, one time.
 */
@Composable
fun WhatsNewAfterUpdate() {
    val ctx = LocalContext.current
    var notes by remember { mutableStateOf(emptyList<ReleaseNotes.Note>()) }
    LaunchedEffect(Unit) { notes = withContext(Dispatchers.IO) { ReleaseNotes.unseen(ctx) } }
    if (notes.isEmpty()) return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun close() { ReleaseNotes.markSeen(ctx); notes = emptyList() }
    SmoothSheet(onDismissRequest = ::close, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp)) {
            Text("What's new", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
            Text("${Brand.name} ${notes.first().version}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(top = 14.dp))
            notes.forEachIndexed { i, n ->
                if (notes.size > 1) Text(n.version, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = if (i == 0) 0.dp else 18.dp, bottom = 6.dp))
                MarkdownText(n.body)
            }
            Spacer(Modifier.padding(top = 18.dp))
            Button(onClick = ::close, modifier = Modifier.fillMaxWidth()) { Text("Got it") }
        }
    }
}
