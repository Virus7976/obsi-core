package app.obsi.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsi.core.ObsiHost
import app.obsi.core.obsidian.Sender
import app.obsi.core.obsidian.VaultNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The notes an Obsi app keeps in your vault, in one place: which ones, in which folder, how many,
 * and when each was last written. Tap one to open it in Obsidian.
 *
 * [folder] is the vault folder the app writes to. [describe] says what a note is for, or null for a
 * note the app did not make. [extra] is the app's own switches, shown above the list.
 */
@Composable
fun VaultNotesPage(
    onBack: () -> Unit,
    title: String,
    folder: String,
    idKey: String,
    describe: (VaultNotes.NoteFile) -> String?,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val ctx = LocalContext.current
    val host = ctx.applicationContext as ObsiHost
    val s by host.settings.state.collectAsState()
    var notes by remember { mutableStateOf<List<VaultNotes.NoteFile>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<VaultNotes.NoteFile?>(null) }
    LaunchedEffect(s.vault, s.vaultFolders, folder, reload) {
        notes = null
        notes = withContext(Dispatchers.IO) { VaultNotes.list(ctx, s, folder, idKey) }
    }
    val vault = VaultNotes.vaultName(s)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.Refresh, "Look again") }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!VaultNotes.hasVault(s)) {
                            Text("No vault folder yet.", style = MaterialTheme.typography.titleSmall)
                            Text("Pick your vault folder in Settings, then Obsidian. Until then nothing is written.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            val list = notes
                            val ours = list?.count { describe(it) != null } ?: 0
                            Text(
                                if (list == null) "Looking…" else if (ours == 1) "1 note" else "$ours notes",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text("In $vault, folder $folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        extra()
                    }
                }
            }
            val list = notes.orEmpty()
            items(list, key = { it.uri }) { n ->
                val what = describe(n)
                Surface(
                    onClick = { Sender.openNote(ctx, vault, "$folder/${n.name}") },
                    shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(n.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (what ?: "Not made by this app") + " · " + ago(n.modified) + " · " + (n.size / 1024).coerceAtLeast(1) + " KB",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (what != null) IconButton(onClick = { deleting = n }) { Icon(Icons.Rounded.DeleteOutline, "Delete ${n.name}") }
                    }
                }
            }
        }
    }
    deleting?.let { n ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${n.name}?") },
            text = { Text("The note leaves your vault. Notes you wrote under its last line go too. The app writes it again the next time it has news.") },
            confirmButton = { TextButton(onClick = { VaultNotes.delete(ctx, n.uri); deleting = null; reload++ }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

private fun ago(ms: Long): String {
    if (ms <= 0) return "never written"
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1 -> "written just now"
        m < 60 -> "written $m min ago"
        m < 60 * 24 -> "written ${m / 60} h ago"
        else -> "written ${m / 60 / 24} days ago"
    }
}
