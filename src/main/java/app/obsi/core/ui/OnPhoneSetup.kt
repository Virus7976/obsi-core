package app.obsi.core.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.obsi.core.Brand
import app.obsi.core.ai.Ai
import app.obsi.core.ai.ModelBridgeService
import app.obsi.core.ai.ModelLinks
import app.obsi.core.ai.OnPhone
import app.obsi.core.ai.PhoneModel
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.launch
import java.util.Locale

private fun gb(bytes: Long) = String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)

/**
 * AI that runs on the phone, with no service and no internet: Gemini Nano when the phone has it
 * built in, else a model the person downloads. The page looks at the phone first and suggests a
 * model only when the phone has the memory for it.
 */
@Composable
internal fun OnPhoneSection() {
    val ctx = LocalContext.current
    val store = remember { Ai.store(ctx) }
    val c by store.state.collectAsState()
    val s by OnPhone.state.collectAsState()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var test by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { OnPhone.check(ctx); OnPhone.watch(ctx) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, uri)?.name.orEmpty()
            runCatching { OnPhone.adopt(ctx, uri, name) }
        }
    }

    Label("On this phone")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainerHigh)) {
        // ---- Gemini Nano, built into some newer phones
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gemini Nano", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        s.builtInBytes != null -> "Getting it… ${gb(s.builtInBytes ?: 0)}"
                        s.builtIn == FeatureStatus.AVAILABLE -> "Ready. Built into this phone."
                        s.builtIn == FeatureStatus.DOWNLOADABLE -> "This phone can get it from Google, free."
                        s.builtIn == FeatureStatus.DOWNLOADING -> "The phone is getting it."
                        s.builtIn == FeatureStatus.UNAVAILABLE -> "Not on this phone."
                        else -> "Looking…"
                    },
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
            if (s.builtIn == FeatureStatus.DOWNLOADABLE && s.builtInBytes == null) FilledTonalButton(onClick = { OnPhone.getBuiltIn(ctx) }) { Text("Get it") }
            if (s.builtInBytes != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = scheme.outlineVariant.copy(alpha = 0.5f))

        // ---- a downloaded model
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val file = s.file
            val dl = s.downloading
            when {
                dl != null -> {
                    Text("Downloading the model", style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(progress = { dl }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${(dl * 100).toInt()}% · Wi-Fi only. It goes on after you leave the app.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { OnPhone.cancelDownload(ctx) }) { Text("Stop") }
                    }
                }
                file != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(PhoneModel.ofFile(file)?.title ?: file.removeSuffix(".litertlm"), style = MaterialTheme.typography.bodyLarge)
                            val where = s.fileApp?.takeIf { it != Brand.app }?.let { " · kept in ${it.title}, shared with this app" } ?: ""
                            Text("Ready. ${gb(s.fileBytes)}$where", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        if (s.fileApp == Brand.app) TextButton(onClick = { scope.launch { OnPhone.remove(ctx) } }) { Text("Remove") }
                    }
                }
                else -> {
                    val pick = remember { OnPhone.suggest(ctx) }
                    if (pick != null) {
                        Text("Suggested for this phone", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(pick.title, style = MaterialTheme.typography.bodyLarge)
                                Text("${pick.blurb} ${gb(pick.bytes)}. Downloads on Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(onClick = { OnPhone.download(ctx, pick) }) { Text("Download") }
                        }
                    } else {
                        Text(
                            String.format(Locale.US, "This phone has %.0f GB of memory. A model needs about %d GB to run well, so none is suggested.", s.ramGb, PhoneModel.entries.minOf { it.minRamGb }),
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Use a model file I have", style = MaterialTheme.typography.bodyMedium, color = scheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { pickFile.launch(arrayOf("*/*")) }.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
    s.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error) }

    if (s.ready) {
        // ---- when the phone answers
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHigh).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("auto" to "Use it", "off" to "Don't use it").forEach { (id, label) ->
                val on = c.onPhone == id
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) scheme.secondaryContainer else Color.Transparent)
                        .clickable { store.update { it.copy(onPhone = id) } }.padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (on) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            if (c.onPhone == "off") "The model on this phone is not used."
            else if (c.ready) "Used when ${c.service?.title ?: "the service"} does not work, like with no internet. Nothing leaves the phone."
            else "Used for all AI. Nothing leaves the phone.",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(enabled = !testing && c.onPhone != "off", onClick = {
                testing = true; test = null
                scope.launch {
                    test = runCatching { Ai.testPhone(ctx) }.fold({ true to "It works. The phone says: \"${it.take(80)}\"" }, { false to (it.message ?: "It did not work.") })
                    testing = false
                }
            }) { Text(if (testing) "Testing…" else "Test it") }
            if (testing) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        }
        test?.let { (ok, text) -> Text(text, style = MaterialTheme.typography.bodyMedium, color = if (ok) scheme.primary else scheme.error) }
        LinkedApps()
    }
}

/**
 * The apps allowed to use this phone's model, like Obsidian's Obsi Clips. They link from their own
 * setup; here the person sees them, and can stop or remove them.
 */
@Composable
private fun LinkedApps() {
    val ctx = LocalContext.current
    val links by ModelLinks.links.collectAsState()
    val on by ModelBridgeService.on.collectAsState()
    LaunchedEffect(Unit) { ModelLinks.load(ctx) }
    val scheme = MaterialTheme.colorScheme

    Label("Other apps on this phone")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainerHigh)) {
        if (links.isEmpty()) {
            Text(
                "Apps like Obsidian can use this model too. In Obsidian, open Obsi Clips' settings, AI, and pick Model in an Obsi app. You are asked here first.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
            return@Column
        }
        links.forEachIndexed { i, l ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = scheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (l.used > 0) "Last used " + android.text.format.DateUtils.getRelativeTimeSpanString(l.used) else "Not used yet",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    ModelLinks.remove(ctx, l)
                    if (ModelLinks.load(ctx).isEmpty()) ModelBridgeService.stop(ctx)
                }) { Text("Remove") }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (on) "On now. It stops after 20 minutes unused." else "Off now. It starts when you open ${Brand.name}.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { if (on) ModelBridgeService.stop(ctx) else ModelBridgeService.start(ctx) }) { Text(if (on) "Stop" else "Start") }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}
