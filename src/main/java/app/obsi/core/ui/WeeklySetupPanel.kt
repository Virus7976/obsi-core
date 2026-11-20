package app.obsi.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsi.core.obsidian.Moment
import app.obsi.core.obsidian.WeeklyNote
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Settings for the weekly note: on or off, and where your weekly notes are. Your setup is found by
 * itself from the vault; every part of it can be changed here. Shared by every Obsi app.
 */
@Composable
fun WeeklyPanel() {
    val ctx = LocalContext.current
    val store = remember { WeeklyNote.store(ctx) }
    val s by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val hasVault = remember { WeeklyNote.hasVault(ctx) }
    LaunchedEffect(Unit) { WeeklyNote.pullFromOthers(ctx) }

    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var pick by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<List<PickItem>>(emptyList()) }
    var editName by remember { mutableStateOf(false) }

    fun find(thenOn: Boolean) {
        busy = true; said = null
        scope.launch {
            val found = runCatching { WeeklyNote.detect(ctx) }.getOrNull()
            if (found == null) said = false to "Could not read the vault."
            else {
                store.update { found.copy(on = thenOn || it.on) }
                said = true to "Found from ${found.foundBy}."
            }
            busy = false
        }
    }
    fun write() {
        busy = true; said = null
        scope.launch {
            said = when (val r = WeeklyNote.update(ctx)) {
                is WeeklyNote.Result.Done -> true to (if (r.made) "Made ${r.path}" else "Updated ${r.path}")
                WeeklyNote.Result.Nothing -> true to "Nothing to write yet this week."
                is WeeklyNote.Result.Failed -> false to r.why
            }
            busy = false
        }
    }
    fun open(kind: String) {
        pick = kind
        options = emptyList()
        scope.launch {
            options = when (kind) {
                "folder" -> listOf(PickItem("", "Top of the vault")) + WeeklyNote.folders(ctx).map { PickItem(it, it.substringAfterLast('/'), it.substringBeforeLast('/', "")) }
                "template" -> listOf(PickItem("", "No template", "A new note starts empty")) + WeeklyNote.templates(ctx).map { PickItem(it, it.substringAfterLast('/').removeSuffix(".md"), it.substringBeforeLast('/', "")) }
                "heading" -> listOf(PickItem("", "End of the note", "Under a heading of its own")) + WeeklyNote.headings(ctx, s).map { PickItem(it, it) }
                else -> listOf(
                    PickItem("locale", "As your phone's region", "Like Obsidian does"),
                    PickItem("monday", "Monday"), PickItem("sunday", "Sunday"),
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(if (s.on) scheme.secondaryContainer else scheme.surfaceContainerHigh)
                .clickable(enabled = hasVault && !busy) { if (!s.on && s.foundBy.isBlank()) find(true) else store.update { it.copy(on = !it.on) } }
                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (s.on) s.path(LocalDate.now()) else "Write my week into my weekly note",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (s.on) scheme.onSecondaryContainer else scheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        !hasVault -> "Link your vault first."
                        s.on -> "Time, what you finished, and your highlights from every Obsi app. Updates when you leave the app."
                        else -> "Time, what you finished, and your highlights from every Obsi app. The rest of the note stays yours."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.on) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = s.on, enabled = hasVault && !busy, onCheckedChange = { on -> if (on && s.foundBy.isBlank()) find(true) else store.update { it.copy(on = on) } })
        }

        if (s.on) {
            Column {
                PickRow("Folder", s.folder.ifBlank { "Top of the vault" }) { open("folder") }
                PickRow("Name", "${s.format}  ·  ${s.name(LocalDate.now())}", mono = true) { editName = true }
                PickRow("Template", s.template.substringAfterLast('/').removeSuffix(".md").ifBlank { "None" }) { open("template") }
                PickRow("Put it under", s.heading.ifBlank { "End of the note" }) { open("heading") }
                PickRow("Week starts", when (s.weekStart) { "monday" -> "Monday"; "sunday" -> "Sunday"; else -> "As your phone's region" }) { open("week") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { write() }, enabled = !busy) { Text("Update now") }
                OutlinedButton(onClick = { find(false) }, enabled = !busy) { Text("Find my setup") }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        said?.let { (ok, text) ->
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (ok) scheme.onSurfaceVariant else scheme.error)
        }
        if (s.on) Text(
            "The apps write only between two hidden markers. Move that part anywhere in the note; it stays where you put it.",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
    }

    pick?.let { kind ->
        QuickPick(
            title = when (kind) { "folder" -> "Weekly notes folder"; "template" -> "Template"; "heading" -> "Put it under"; else -> "Week starts" },
            items = options,
            selected = when (kind) { "folder" -> s.folder; "template" -> s.template; "heading" -> s.heading; else -> s.weekStart },
            onPick = { p ->
                store.update {
                    when (kind) {
                        "folder" -> it.copy(folder = p.id.trim('/'))
                        "template" -> it.copy(template = p.id)
                        "heading" -> it.copy(heading = p.id)
                        else -> it.copy(weekStart = p.id)
                    }
                }
                pick = null
            },
            onDismiss = { pick = null },
            empty = "Reading the vault…",
            allowTyped = kind == "folder" || kind == "heading",
        )
    }
    if (editName) TextDialog(
        title = "Note name",
        initial = s.format,
        mono = true,
        help = "The same format as Obsidian: gggg-[W]ww is ${Moment.format(LocalDate.now(), "gggg-[W]ww", s.week)}. Text in [brackets] stays as it is.",
        onDone = { v -> if (v.isNotBlank()) store.update { it.copy(format = v.trim()) }; editName = false },
        onDismiss = { editName = false },
    )
}

@Composable
private fun PickRow(title: String, value: String, mono: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = scheme.onSurfaceVariant)
    }
}
