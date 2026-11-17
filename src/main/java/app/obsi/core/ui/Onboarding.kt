package app.obsi.core.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.obsi.core.Brand
import app.obsi.core.ObsiHost
import app.obsi.core.data.AppSettings
import app.obsi.core.share.ObsiSetup
import app.obsi.core.share.SharedSetup

/** One page of an app's own first-run steps. [ready] false greys out Next until the step is done; most steps can be skipped. */
class OnboardingStep(
    val title: String,
    val ready: () -> Boolean = { true },
    val content: @Composable () -> Unit,
)

object Onboarding {
    /** First run: no vault folder yet and the walk-through not finished. A phone already set up never sees it. */
    fun needed(s: AppSettings): Boolean = !s.onboarded && s.vaultFolders.isEmpty()

    /** The vault that has a folder: the chosen one, or else the first linked one. Blank when none is linked. */
    fun linkedVault(s: AppSettings): String = s.vault.takeIf { it in s.vaultFolders } ?: s.vaultFolders.keys.firstOrNull().orEmpty()

    /** The name of the folder behind the linked vault, as you see it in the file picker. Blank when none is linked. */
    fun folderName(s: AppSettings): String = s.vaultFolders[linkedVault(s)]?.let {
        android.net.Uri.decode(it).substringAfterLast(':').substringAfterLast('/')
    }.orEmpty()
}

/**
 * The first-run walk-through every Obsi app shares: what the app is, your vault, then the app's own steps.
 * When another Obsi app is set up, the vault step is one tap, and the app says so.
 */
@Composable
fun OnboardingScreen(pitch: String, steps: List<OnboardingStep>, onDone: () -> Unit) {
    val context = LocalContext.current
    val host = context.applicationContext as ObsiHost
    val scheme = MaterialTheme.colorScheme
    val settings by host.settings.state.collectAsState()
    var others by remember { mutableStateOf(emptyList<ObsiSetup>()) }
    val installed = remember { Brand.others.filter { it.installed(context) } }
    LaunchedEffect(Unit) { others = SharedSetup.fromOthers(context) }

    val titles = listOf("Welcome", "Your vault") + steps.map { it.title } + "Ready"
    var page by remember { mutableIntStateOf(0) }
    val last = titles.lastIndex
    fun finish() { host.settings.update { it.copy(onboarded = true) }; onDone() }
    BackHandler(enabled = page > 0) { page-- }

    Column(Modifier.fillMaxSize().background(scheme.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                titles.indices.forEach { i ->
                    Box(Modifier.size(width = if (i == page) 22.dp else 8.dp, height = 8.dp).clip(CircleShape)
                        .background(if (i <= page) scheme.primary else scheme.outlineVariant))
                }
            }
            if (page < last) TextButton(onClick = { finish() }) { Text("Skip") }
        }
        AnimatedContent(targetState = page, modifier = Modifier.weight(1f), label = "onboarding") { p ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(12.dp))
                when (p) {
                    0 -> Welcome(pitch, installed.map { it.title })
                    1 -> VaultStep(settings, others)
                    last -> {
                        Text("You are set", style = MaterialTheme.typography.headlineMedium)
                        Text("Everything here is in Settings too, so you can change it later.", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    }
                    else -> {
                        val s = steps[p - 2]
                        Text(s.title, style = MaterialTheme.typography.headlineMedium)
                        s.content()
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) TextButton(onClick = { page-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            val stepReady = page !in 2 until last || steps[page - 2].ready()
            Button(onClick = { if (page == last) finish() else page++ }, enabled = stepReady) {
                Text(when (page) { 0 -> "Start"; last -> "Open ${Brand.name}"; else -> "Next" })
            }
        }
    }
}

@Composable
private fun Welcome(pitch: String, others: List<String>) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val icon = remember { runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull() }
    icon?.let { Image(it, null, Modifier.size(96.dp).clip(RoundedCornerShape(24.dp))) }
    Text("Welcome to ${Brand.name}", style = MaterialTheme.typography.headlineMedium)
    Text(pitch, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
    if (others.isNotEmpty()) Surface(shape = RoundedCornerShape(16.dp), color = scheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("You have " + (if (others.size > 1) others.dropLast(1).joinToString(", ") + " and " + others.last() else others.single()), style = MaterialTheme.typography.titleSmall, color = scheme.onSecondaryContainer)
            Text("${Brand.name} uses the same vault, tags and highlights. This takes a few taps.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun VaultStep(settings: AppSettings, others: List<ObsiSetup>) {
    val context = LocalContext.current
    val host = context.applicationContext as ObsiHost
    val scheme = MaterialTheme.colorScheme
    var from by remember { mutableStateOf<ObsiSetup?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val setup = from
        from = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        if (setup != null) host.settings.update { SharedSetup.apply(it, setup, uri.toString()) }
        else {
            val name = android.net.Uri.decode(uri.toString()).substringAfterLast(':').substringAfterLast('/').ifBlank { "Vault" }
            // The folder you picked is the vault now, even if a template named another one first.
            host.settings.update { it.copy(vaults = (it.vaults + name).distinct(), vault = if (it.vault in it.vaultFolders) it.vault else name, vaultFolders = it.vaultFolders + (name to uri.toString()), vaultFolderUri = uri.toString()) }
        }
        val name = host.settings.value.vault
        if (name.isNotBlank()) host.vaults.refresh(name, force = true)
    }
    Text("Your vault", style = MaterialTheme.typography.headlineMedium)
    Text("${Brand.name} saves notes into your Obsidian vault folder. Pick it once.", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
    val linked = Onboarding.folderName(settings).ifBlank { Onboarding.linkedVault(settings) }.ifBlank { null }
    if (linked != null) {
        Surface(shape = RoundedCornerShape(16.dp), color = scheme.primaryContainer.copy(alpha = 0.4f), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = scheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Linked: $linked", style = MaterialTheme.typography.titleMedium)
            }
        }
        TextButton(onClick = { from = null; picker.launch(null) }) { Text("Pick a different folder") }
        return
    }
    others.firstOrNull()?.let { o ->
        Button(onClick = { from = o; picker.launch(android.net.Uri.parse(o.folder)) }, modifier = Modifier.fillMaxWidth()) {
            Text("Use “${o.vault.ifBlank { "your vault" }}”, like ${o.app.title}")
        }
        Text("Android shows the folder picker at that vault. Tap Use this folder.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
    FilledTonalButton(onClick = { from = null; picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.Folder, null)
        Spacer(Modifier.width(8.dp))
        Text(if (others.isEmpty()) "Pick the vault folder" else "Pick another folder")
    }
    Text("No vault yet? Skip this. You can pick it later in Settings, then Obsidian.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
}
