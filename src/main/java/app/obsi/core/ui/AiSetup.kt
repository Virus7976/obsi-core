package app.obsi.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsi.core.Brand
import app.obsi.core.ObsiApp
import app.obsi.core.ai.Ai
import app.obsi.core.ai.AiModel
import app.obsi.core.ai.AiProvider
import kotlinx.coroutines.launch

/**
 * Set up AI once for every Obsi app: pick a service, paste its key, pick a model, test it.
 * What you set here shows in the other Obsi apps too.
 */
@Composable
fun AiPanel() {
    val ctx = LocalContext.current
    val store = remember { Ai.store(ctx) }
    val c by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { Ai.pullFromOthers(ctx) }
    val phone by app.obsi.core.ai.OnPhone.state.collectAsState()
    val usable by Ai.usable.collectAsState()

    val p = c.service
    var showKey by remember { mutableStateOf(false) }
    var pickModel by remember { mutableStateOf(false) }
    var pickSpeech by remember { mutableStateOf(false) }
    val lists = remember { mutableStateMapOf<String, List<AiModel>>() }
    var loading by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }
    var test by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun loadModels(force: Boolean = false) {
        val prov = c.service ?: return
        if (!force && lists[prov.id] != null) return
        if (prov.needsKey && c.key(prov).isBlank()) return
        loading = true; listError = null
        scope.launch {
            runCatching { Ai.models(store.value, prov) }
                .onSuccess { list ->
                    listError = null
                    lists[prov.id] = list
                    // A model the service does not have any more is swapped for its default.
                    val cur = store.value.models[prov.id]
                    if ((cur.isNullOrBlank() || list.none { it.id == cur }) && list.isNotEmpty()) {
                        Ai.pickDefault(prov, list)?.let { m -> store.update { it.copy(models = it.models + (prov.id to m)) } }
                    }
                }
                .onFailure { listError = it.message }
            loading = false
        }
    }
    LaunchedEffect(c.provider, c.key(p ?: AiProvider.OpenRouter), c.urls[c.provider]) { test = null; loadModels() }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- where it stands: what answers now, or why nothing does
        val using = remember(c, phone, usable) { Ai.using(ctx) }
        val phoneName = when {
            phone.builtInReady -> "Gemini Nano"
            phone.file != null -> app.obsi.core.ai.PhoneModel.ofFile(phone.file!!)?.title ?: "The model on this phone"
            else -> ""
        }
        val (title, about) = when (using) {
            "service" -> modelName(c.model(p!!), lists[p.id]) to (
                "Through ${p.title}. Finds things by meaning in search, and suggests tags." +
                    if (phone.ready && c.onPhone != "off") " With no internet, $phoneName on this phone takes over." else ""
                )
            "phone" -> phoneName to (
                if (c.ready) "No internet, so the model on this phone answers. Nothing leaves the phone."
                else "Runs on this phone, with no internet. Finds things by meaning in search, and suggests tags."
                )
            else -> if (c.ready || phone.ready) "Off right now" to (
                "No internet, and no model on this phone to use. Find by meaning and tag ideas are hidden until one works. Everything else works the same."
                ) else "Not set up" to (
                "Optional. With a service or a model on this phone, search finds your highlights by meaning, and tags are suggested when you add them."
                )
        }
        val on = using.isNotEmpty()
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(if (on) scheme.secondaryContainer else scheme.surfaceContainerHigh).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (on) Icons.Rounded.CheckCircle else Icons.Rounded.Key, null,
                tint = if (on) scheme.primary else scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (on) scheme.onSecondaryContainer else scheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(about, style = MaterialTheme.typography.bodySmall, color = if (on) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
            }
        }

        // ---- the services: the main four, the rest behind one row
        Label("Service")
        val main = listOf(AiProvider.OpenRouter, AiProvider.Anthropic, AiProvider.OpenAi, AiProvider.Google)
        var more by remember { mutableStateOf(p != null && p !in main) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainerHigh)) {
            val shown = if (more) AiProvider.entries else main
            shown.forEachIndexed { i, prov ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 52.dp), color = scheme.outlineVariant.copy(alpha = 0.5f))
                ProviderRow(prov, selected = prov == p, hasKey = c.key(prov).isNotBlank()) {
                    store.update { it.copy(provider = if (it.provider == prov.id) "" else prov.id) }
                }
            }
            if (!more) {
                androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 52.dp), color = scheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().clickable { more = true }.padding(start = 52.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
                    Text("More services", style = MaterialTheme.typography.bodyLarge, color = scheme.primary, modifier = Modifier.weight(1f))
                    Text(AiProvider.entries.filter { it !in main }.joinToString(", ") { it.title }, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.4f))
                }
            }
        }

        AnimatedVisibility(p != null) {
            if (p == null) return@AnimatedVisibility
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                Label(p.title)
                if (p.needsUrl) OutlinedTextField(
                    value = c.urls[p.id].orEmpty().ifBlank { if (p == AiProvider.Ollama) "" else "" },
                    onValueChange = { v -> store.update { it.copy(urls = it.urls + (p.id to v.trim())) } },
                    label = { Text("Address") },
                    placeholder = { Text(if (p == AiProvider.Ollama) "http://192.168.1.10:11434/v1" else "https://example.com/v1") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText = { Text(if (p == AiProvider.Ollama) "Your computer's address on the same Wi-Fi, port 11434, ending in /v1." else "The address that ends in /v1.") },
                )
                if (p.needsKey || p == AiProvider.Custom) OutlinedTextField(
                    value = c.keys[p.id].orEmpty(),
                    onValueChange = { v -> store.update { it.copy(keys = it.keys + (p.id to v.trim())) } },
                    label = { Text(if (p.needsKey) "Key" else "Key, if it needs one") },
                    placeholder = { if (p.keyHint.isNotBlank()) Text(p.keyHint) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showKey) "Hide the key" else "Show the key")
                        }
                    },
                    supportingText = { Text("Stays on this phone. It goes only to ${p.title}.") },
                )
                if (p.keyPage.isNotBlank()) OutlinedButton(onClick = { runCatching { uri.openUri(p.keyPage) } }) {
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (p == AiProvider.Ollama) "Get Ollama" else "Get a key from ${p.title}")
                }

                // ---- the model
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHigh)
                        .clickable { loadModels(); pickModel = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Model", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        Text(c.model(p).ifBlank { "Pick a model" }.let { modelName(it, lists[p.id]) }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.ChevronRight, null, tint = scheme.onSurfaceVariant)
                }
                listError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error) }

                // ---- test
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(enabled = c.ready && !testing, onClick = {
                        testing = true; test = null
                        scope.launch {
                            test = runCatching { Ai.test(ctx) }.fold({ true to "It works. ${p.title} says: \"${it.take(80)}\"" }, { false to (it.message ?: "It did not work.") })
                            testing = false
                        }
                    }) { Text(if (testing) "Testing…" else "Test it") }
                    if (testing) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                }
                test?.let { (ok, text) ->
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (ok) scheme.primary else scheme.error)
                }
            }
        }

        // ---- AI on the phone: Gemini Nano, or a downloaded model
        OnPhoneSection()

        // ---- tag ideas: by themselves, or on a tap
        if (c.ready || phone.ready) {
            Label("Tag ideas")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHigh).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("auto" to "By themselves", "ask" to "When I tap").forEach { (id, label) ->
                    val on = c.tagIdeas == id
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) scheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { store.update { it.copy(tagIdeas = id) } }.padding(vertical = 11.dp),
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
                if (c.tagIdeas == "ask") "A small Suggest tags button sits under each tag field. Nothing is sent until you tap it."
                else "When you add a quote, a few tags appear once you finish your note. They read the quote and your note. Your own tags come first.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }

        // ---- transcripts: only the app that makes them (ObsiTube gets YouTube's own)
        if (Brand.app == ObsiApp.Learn) {
            Label("Transcripts")
            val sp = c.speech
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHigh)
                    .clickable { pickSpeech = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Make transcripts with", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Text(sp?.let { "${it.title} (${it.speechModel})" } ?: "Not set: add an OpenRouter, Groq or OpenAI key", style = MaterialTheme.typography.bodyLarge)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = scheme.onSurfaceVariant)
            }
            Text(
                "Used when a ${Brand.noun} has no transcript of its own and you pick the online engine. Groq is fast and has a free tier.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }

        Text(
            "Set up once: ${ObsiApp.entries.joinToString(", ") { it.title }} all use this setup.",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
    }

    if (pickModel && p != null) QuickPick(
        title = "Model",
        items = lists[p.id].orEmpty().map { PickItem(it.id, it.name, if (it.name != it.id) it.id else "", it.note) },
        selected = c.model(p),
        onPick = { m -> store.update { it.copy(models = it.models + (p.id to m.id)) }; pickModel = false },
        onDismiss = { pickModel = false },
        placeholder = "Find a model",
        empty = if (loading) "Loading the models…" else listError ?: if (p.needsKey && c.key(p).isBlank()) "Paste the key first." else "No models found.",
        allowTyped = true,
    )

    if (pickSpeech) QuickPick(
        title = "Make transcripts with",
        items = AiProvider.entries.filter { it.makesTranscripts }.map { prov ->
            PickItem(prov.id, prov.title, if (prov.needsKey && c.key(prov).isBlank()) "Needs a ${prov.title} key: pick it above first" else prov.speechModel)
        },
        selected = c.speech?.id,
        onPick = { s -> store.update { it.copy(speechProvider = s.id) }; pickSpeech = false },
        onDismiss = { pickSpeech = false },
    )
}

/** A model's own name from the list, else its id. */
private fun modelName(id: String, list: List<AiModel>?): String = list?.firstOrNull { it.id == id }?.name ?: id

@Composable
private fun ProviderRow(p: AiProvider, selected: Boolean, hasKey: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.CheckCircle, "Picked", tint = scheme.primary)
            else Box(Modifier.size(18.dp).border(BorderStroke(1.5.dp, scheme.outline), RoundedCornerShape(50)))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal))
            Text(p.blurb, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (hasKey && !selected) Text("Key added", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}
