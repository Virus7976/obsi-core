package app.obsi.core.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.obsi.core.Brand
import app.obsi.core.ObsiApp
import app.obsi.core.share.SharedBackup
import kotlinx.coroutines.launch

/**
 * Save every Obsi app to one file, or put one back. Shown in Settings › Backup of each app, and the
 * same in all of them: any app can save or restore all of them.
 */
@Composable
fun BackupPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var restoreFrom by remember { mutableStateOf<Pair<android.net.Uri, List<ObsiApp>>?>(null) }
    var restarting by remember { mutableStateOf(false) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Saving…"
        scope.launch {
            message = runCatching { SharedBackup.backupAll(context, uri) }.fold(
                { r -> "Saved ${names(r.apps)}. ${r.files} files, ${size(r.bytes)}." },
                { "Could not save the backup. ${it.message.orEmpty()}".trim() },
            )
            busy = null
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val apps = SharedBackup.appsIn(context, uri)
            if (apps.isEmpty()) message = "That file is not an Obsi backup."
            else restoreFrom = uri to apps
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "One file holds every Obsi app on this phone: settings, libraries, highlights, review, stats and notes. " +
                "Downloaded videos, Bibles and speech models are not in it. Download them again after.",
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
        )
        Text(
            "The file has your keys for online services in it. Keep it private.",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = busy == null, onClick = { saver.launch("Obsi backup ${java.time.LocalDate.now()}.zip") }) {
                Icon(Icons.Rounded.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save a backup")
            }
            FilledTonalButton(enabled = busy == null, onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                Icon(Icons.Rounded.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Put one back")
            }
        }
        busy?.let { b ->
            Column {
                Text(b, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        message?.let { m ->
            Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Text(m, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    restoreFrom?.let { (uri, apps) ->
        val here = apps.filter { it == Brand.app || it.installed(context) }
        val missing = apps - here.toSet()
        AlertDialog(
            onDismissRequest = { restoreFrom = null },
            title = { Text("Put this backup back?") },
            text = {
                Text(
                    "This replaces the settings, libraries and highlights in ${names(here)}. Those apps close and start again." +
                        (if (missing.isNotEmpty()) " ${names(missing)} ${if (missing.size == 1) "is" else "are"} not on this phone, so ${if (missing.size == 1) "it is" else "they are"} skipped. Install ${if (missing.size == 1) "it" else "them"} first to put ${if (missing.size == 1) "it" else "them"} back too." else ""),
                )
            },
            confirmButton = {
                Button(onClick = {
                    restoreFrom = null
                    busy = "Putting it back…"
                    scope.launch {
                        val r = runCatching { SharedBackup.restoreAll(context, uri) }
                        busy = null
                        r.onSuccess { done ->
                            message = "Put back ${names(done.apps)}."
                            if (Brand.app in done.apps) restarting = true
                        }.onFailure { message = "Could not put the backup back. ${it.message.orEmpty()}".trim() }
                    }
                }) { Text("Put it back") }
            },
            dismissButton = { TextButton(onClick = { restoreFrom = null }) { Text("Cancel") } },
        )
    }
    if (restarting) AlertDialog(
        onDismissRequest = {},
        title = { Text("Done") },
        text = { Text("${Brand.app.title} closes now. Open it again to see your backup.") },
        confirmButton = { Button(onClick = { SharedBackup.restartSoon() }) { Text("Close ${Brand.app.title}") } },
    )
}

private fun names(apps: List<ObsiApp>): String = apps.map { it.title }.let {
    when (it.size) { 0 -> "nothing"; 1 -> it[0]; else -> it.dropLast(1).joinToString(", ") + " and " + it.last() }
}

private fun size(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes bytes"
}
