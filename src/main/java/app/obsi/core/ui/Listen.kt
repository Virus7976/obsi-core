package app.obsi.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsi.core.speech.KeepState
import app.obsi.core.speech.ListenDoc
import app.obsi.core.speech.PhoneVoices
import app.obsi.core.speech.ReadAloud
import app.obsi.core.speech.SLEEP_END
import app.obsi.core.speech.SpeakerVoice
import app.obsi.core.speech.VoiceKind

/** "1x", "1.25x". */
fun speedLabel(x: Float): String = "%.2f".format(x).trimEnd('0').trimEnd('.') + "x"

/**
 * The small player that sits above the bottom bar while something is read aloud: play or pause, back and
 * forward a sentence, the speed. A tap on the words opens the full sheet.
 */
@Composable
fun ListenBar(engine: ReadAloud, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val u by engine.ui.collectAsState()
    val scheme = MaterialTheme.colorScheme
    AnimatedVisibility(visible = u.active, modifier = modifier) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(22.dp),
            color = scheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
        ) {
            Column {
                val p by animateFloatAsState(if (u.lines > 0) (u.line + 1f) / u.lines else 0f, label = "listen")
                Box(Modifier.fillMaxWidth().height(2.dp).background(scheme.outlineVariant.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxWidth(p).height(2.dp).background(scheme.primary))
                }
                Row(Modifier.padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = { engine.toggle() }, modifier = Modifier.size(44.dp)) {
                        if (u.loading && !u.playing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                        else Icon(if (u.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (u.playing) "Pause" else "Play")
                    }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Read aloud controls", onClick = onOpen).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(u.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                        Text(
                            listOfNotNull(u.voice?.name, if (u.minutesLeft > 0) "${u.minutesLeft} min left" else null).joinToString(" · "),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { engine.previous() }) { Icon(Icons.Rounded.SkipPrevious, "Back a sentence") }
                    IconButton(onClick = { engine.next() }) { Icon(Icons.Rounded.SkipNext, "Next sentence") }
                    TextButton(onClick = { engine.setSpeed(nextSpeed(u.speed)) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(speedLabel(u.speed), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private fun nextSpeed(x: Float) = SPEEDS.firstOrNull { it > x + 0.01f } ?: SPEEDS.first()

/**
 * The player, like a music app's: what is playing, where it is, the three big controls, and one row of
 * small buttons. Speed, voice, sleep timer and offline audio each open their own page, so the sheet stays
 * calm. [doc] is what this screen would read; [fromPara] where to start when nothing is playing.
 */
@Composable
fun ListenSheet(engine: ReadAloud, doc: ListenDoc?, fromPara: () -> Int, onDismiss: () -> Unit) {
    val u by engine.ui.collectAsState()
    val keeps by engine.keeps.collectAsState()
    val scheme = MaterialTheme.colorScheme
    var page by remember { mutableStateOf("main") }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(doc?.id, u.voice?.id) { doc?.let { engine.refreshKeep(it) } }
    DisposableEffect(Unit) { onDispose { engine.stopPreview() } }
    androidx.activity.compose.BackHandler(enabled = page != "main") { page = "main" }
    SmoothSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        androidx.compose.animation.AnimatedContent(page, label = "listenPage") { p ->
        when (p) {
            "voices" -> VoicePicker(engine, doc?.locale ?: "en-US", onBack = { page = "main" })
            "speed" -> SubPage("Speed", onBack = { page = "main" }) {
                var speed by remember(u.speed) { mutableFloatStateOf(u.speed) }
                Text(speedLabel(speed), style = MaterialTheme.typography.displaySmall, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
                Slider(value = speed, onValueChange = { speed = Math.round(it * 20) / 20f }, onValueChangeFinished = { engine.setSpeed(speed) }, valueRange = 0.5f..3f)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    for (x in listOf(0.75f, 1f, 1.25f, 1.5f, 2f)) {
                        FilterChip(selected = kotlin.math.abs(u.speed - x) < 0.01f, onClick = { engine.setSpeed(x) }, label = { Text(speedLabel(x)) })
                    }
                }
            }
            "sleep" -> SubPage("Sleep timer", onBack = { page = "main" }) {
                val opts = listOf(0 to "Off", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", SLEEP_END to "At the end of the article")
                for ((m, label) in opts) PickRow(label, u.sleepMinutes == m) { engine.setSleep(m); page = "main" }
            }
            "offline" -> SubPage("Offline audio", onBack = { page = "main" }) {
                val v = doc?.let { engine.keepVoice(it.locale) }
                if (doc != null && v != null) OfflineRow(engine, doc, v, keeps[doc.id])
                Text(
                    "Saved audio plays with no internet. Change what is saved, and when, in Settings, Read aloud.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
            else -> Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
                val here = doc != null && u.active && u.docId == doc.id
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(if (here || doc == null) u.title.ifBlank { doc?.title.orEmpty() } else doc.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = if (u.active && !here) "Now playing: ${u.title}" else (u.voice?.name ?: engine.pickVoice(doc?.locale ?: "en-US")?.name).orEmpty()
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (u.active) TextButton(onClick = { engine.stop(); onDismiss() }) { Text("Stop") }
                }

                // Where it is: drag to jump. Under it, the sentence and the time left.
                Spacer(Modifier.height(16.dp))
                var drag by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = drag ?: if (here && u.lines > 1) u.line.toFloat() / (u.lines - 1) else 0f,
                    onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { f -> engine.seekToLine((f * (u.lines - 1)).toInt()) }; drag = null },
                    enabled = here && u.lines > 1,
                )
                Row(Modifier.fillMaxWidth()) {
                    val shown = drag?.let { (it * (u.lines - 1)).toInt() } ?: u.line
                    Text(if (here) "${shown + 1} of ${u.lines}" else "", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(if (here && u.minutesLeft > 0) "${u.minutesLeft} min left" else "", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }

                // The big controls.
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { engine.previous() }, enabled = here, modifier = Modifier.size(64.dp)) { Icon(Icons.Rounded.SkipPrevious, "Back a sentence", Modifier.size(36.dp)) }
                    FilledIconButton(
                        onClick = { if (here) engine.toggle() else doc?.let { engine.play(it, fromPara()) } },
                        modifier = Modifier.size(80.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.primary),
                    ) {
                        val playing = here && u.playing
                        if (here && u.loading && !u.playing) CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp, color = scheme.onPrimary)
                        else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(40.dp))
                    }
                    IconButton(onClick = { engine.next() }, enabled = here, modifier = Modifier.size(64.dp)) { Icon(Icons.Rounded.SkipNext, "Next sentence", Modifier.size(36.dp)) }
                }
                u.error?.let { e ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(scheme.errorContainer).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onErrorContainer)
                        IconButton(onClick = { engine.clearError() }) { Icon(Icons.Rounded.Close, "Hide", tint = scheme.onErrorContainer) }
                    }
                }

                // One quiet row: each opens its own page.
                val k = doc?.let { keeps[it.id] }
                val saveVoice = doc?.let { engine.keepVoice(it.locale) }
                val offlineLabel = when {
                    saveVoice?.online == false -> "Offline"
                    k?.running == true -> "${k.done * 100 / k.total.coerceAtLeast(1)}%"
                    k?.complete == true && k.voiceId == saveVoice?.id -> "Saved"
                    k?.error != null -> "Paused"
                    else -> "Save"
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BarButton(Icons.Rounded.Speed, speedLabel(u.speed), active = kotlin.math.abs(u.speed - 1f) > 0.01f) { page = "speed" }
                    BarButton(Icons.Rounded.RecordVoiceOver, "Voice", active = false) { page = "voices" }
                    BarButton(
                        Icons.Rounded.Bedtime,
                        if (u.sleepMinutes > 0) "%d:%02d".format(u.sleepLeft / 60_000, (u.sleepLeft / 1000) % 60) else if (u.sleepMinutes == SLEEP_END) "At end" else "Sleep",
                        active = u.sleepMinutes != 0,
                    ) { page = "sleep" }
                    if (doc != null) BarButton(
                        if (k?.complete == true || saveVoice?.online == false) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                        offlineLabel, active = k?.complete == true || k?.running == true,
                    ) { page = "offline" }
                }
            }
        }
        }
    }
}

/** A small icon with a word under it, for the row at the bottom of the player. */
@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (active) scheme.primary else scheme.onSurfaceVariant
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp).width(60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint)
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A page inside the player sheet, with a back arrow. */
@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), content = content)
    }
}

/** One choice in a list, with a check on the one picked. */
@Composable
private fun PickRow(label: String, picked: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (picked) Icon(Icons.Rounded.Check, "Picked", tint = MaterialTheme.colorScheme.primary)
    }
}

private fun voiceDetail(v: SpeakerVoice): String = listOfNotNull(
    v.place(),
    when (v.kind) { VoiceKind.PHONE -> "Offline"; VoiceKind.PHONE_ONLINE -> "Google online"; VoiceKind.MICROSOFT -> "Microsoft"; VoiceKind.EDGE -> "Microsoft Edge" },
).joinToString(" · ")

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, detail: String?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, style = MaterialTheme.typography.labelLarge, color = scheme.primary, maxLines = 1)
        Icon(Icons.Rounded.ChevronRight, null, tint = scheme.onSurfaceVariant)
    }
}

/** "Save audio for offline", how far it got, or that it is kept. */
@Composable
private fun OfflineRow(engine: ReadAloud, doc: ListenDoc, voice: SpeakerVoice, k: KeepState?) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon = when {
            !voice.online -> Icons.Rounded.CloudDone
            k?.complete == true && k.voiceId == voice.id -> Icons.Rounded.DownloadDone
            else -> Icons.Rounded.Download
        }
        Icon(icon, null, tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Offline audio", style = MaterialTheme.typography.titleSmall)
            val text = when {
                !voice.online -> "This voice works with no internet."
                k?.running == true -> "Saving ${k.done} of ${k.total}…"
                k?.error != null -> k.error
                k?.complete == true && k.voiceId == voice.id -> "Saved on this phone with ${voice.name}."
                k?.partly == true && k.voiceId == voice.id -> "Partly saved: ${k.done} of ${k.total}."
                else -> "Save it to listen with no internet."
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (k?.running == true && k.total > 0) {
                androidx.compose.material3.LinearProgressIndicator(progress = { k.done.toFloat() / k.total }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        if (voice.online) when {
            k?.running == true -> IconButton(onClick = { engine.stopKeep(doc.id) }) { Icon(Icons.Rounded.Close, "Stop saving") }
            k?.complete == true && k.voiceId == voice.id -> IconButton(onClick = { engine.removeKept(doc.id) }) { Icon(Icons.Rounded.Delete, "Delete the saved audio") }
            else -> TextButton(onClick = { engine.keep(doc) }) { Text(if (k?.partly == true) "Finish" else "Save") }
        }
    }
}

/**
 * The voice list, like a store shelf: the phone's offline voices, Google's online ones, and Microsoft's
 * with a key. Each has a play button for a sample. Only voices for the text's language, unless asked.
 */
@Composable
fun VoicePicker(engine: ReadAloud, locale: String, onBack: () -> Unit, title: String = "Voice", isDefault: Boolean = false) {
    val all by engine.voices.collectAsState()
    val u by engine.ui.collectAsState()
    val err by engine.voicesError.collectAsState()
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var everyLanguage by remember { mutableStateOf(false) }
    var addKey by remember { mutableStateOf(false) }
    val lang = locale.substringBefore('-').lowercase()
    val current = if (isDefault) engine.defaultVoice(locale)?.id else (u.voice?.id ?: engine.pickVoice(locale)?.id)
    // The one in use first, then voices for this exact place (en-US), then multilingual ones, then the rest.
    val shown = (if (everyLanguage) all else all.filter { it.language == lang }).sortedWith(
        compareBy<SpeakerVoice>({ it.id != current }, { !it.locale.equals(locale, true) }, { !it.note.contains("Multilingual") }, { it.locale }, { it.name }),
    )
    // New phone voices appear after a download in the phone's settings: look again on return.
    OnResume { engine.refreshVoices() }
    var openKinds by remember { mutableStateOf(setOfNotNull(all.firstOrNull { it.id == current }?.kind ?: VoiceKind.PHONE)) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { everyLanguage = !everyLanguage }) { Text(if (everyLanguage) "My language" else "All languages") }
        }
        LazyColumn(Modifier.fillMaxWidth().then(if (isDefault) Modifier.fillMaxHeight() else Modifier.heightIn(max = 620.dp)), contentPadding = PaddingValues(bottom = 24.dp)) {
            val groups = listOf(
                Triple(VoiceKind.EDGE, "Microsoft Edge", "Free natural voices. Needs the internet. Saved as it plays, so it works offline after."),
                Triple(VoiceKind.PHONE, "On this phone", "Works with no internet."),
                Triple(VoiceKind.PHONE_ONLINE, "Google online", "Needs the internet once. Saved as it plays, so it works offline after."),
                Triple(VoiceKind.MICROSOFT, "Microsoft with your key", "Your own free Azure key. Use it if Edge voices stop."),
            )
            // One card per service. Tap a service to open its voices. The one in use starts open.
            for ((kind, title, note) in groups) {
                val list = shown.filter { it.kind == kind }
                if (list.isEmpty() && kind != VoiceKind.MICROSOFT) continue
                val isOpen = kind in openKinds
                val inUse = list.firstOrNull { it.id == current }
                // Each voice is its own row in the list, drawn so the group looks like one card.
                val card = scheme.surfaceContainerHigh
                val big = 20.dp
                val showKey = isOpen && kind == VoiceKind.MICROSOFT && (list.isEmpty() || addKey)
                val showChange = isOpen && kind == VoiceKind.MICROSOFT && list.isNotEmpty() && !addKey
                val rows = if (isOpen) list.size else 0
                val hasTail = showKey || showChange
                item(key = "g-$kind") {
                    val shape = if (rows == 0 && !hasTail) RoundedCornerShape(big) else RoundedCornerShape(topStart = big, topEnd = big)
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp).fillMaxWidth().clip(shape).background(card)) {
                        Row(
                            Modifier.fillMaxWidth().clickable(onClickLabel = if (isOpen) "Close $title" else "Open $title") {
                                openKinds = if (isOpen) openKinds - kind else openKinds + kind
                            }.heightIn(min = 64.dp).padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    inUse?.let { "In use: ${it.name}" } ?: note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (inUse != null) scheme.primary else scheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (list.isNotEmpty()) Text("${list.size}", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                            val turn by animateFloatAsState(if (isOpen) 180f else 0f, label = "chevron")
                            Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(turn), tint = scheme.onSurfaceVariant)
                        }
                        if (isOpen && inUse == null) Text(note, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                if (isOpen) list.forEachIndexed { i, v ->
                    item(key = "v-${v.id}") {
                        val last = i == list.lastIndex && !hasTail
                        val shape = if (last) RoundedCornerShape(bottomStart = big, bottomEnd = big) else RoundedCornerShape(0.dp)
                        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(card)) {
                            HorizontalDivider(Modifier.padding(start = 76.dp), color = scheme.outlineVariant.copy(alpha = 0.4f))
                            VoiceRow(v, picked = v.id == current, onPlay = { engine.preview(v) }, onPick = { if (isDefault) engine.setDefaultVoice(v) else engine.setVoice(v) })
                        }
                    }
                }
                if (hasTail) item(key = "t-$kind") {
                    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(bottomStart = big, bottomEnd = big)).background(card).padding(bottom = 8.dp)) {
                        if (showKey) MicrosoftKey(engine, err, open = addKey || engine.prefs.msKey.isBlank(), onOpen = { addKey = true }, onDone = { addKey = false })
                        if (showChange) TextButton(onClick = { addKey = true }, modifier = Modifier.padding(start = 8.dp)) { Text("Change the key") }
                    }
                }
            }
            item(key = "more") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    TextButton(onClick = { if (!PhoneVoices.openVoiceDownloads(ctx)) android.widget.Toast.makeText(ctx, "This phone has no page for more voices.", android.widget.Toast.LENGTH_LONG).show() }) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Get more phone voices")
                    }
                    Text(
                        "Opens your phone's voice settings. Google's high quality voices download there for free and then work with no internet.",
                        Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(v: SpeakerVoice, picked: Boolean, onPlay: () -> Unit, onPick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Use ${v.name}", onClick = onPick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(scheme.surfaceContainerHighest).clickable(onClickLabel = "Hear ${v.name}", onClick = onPlay), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "Hear ${v.name}")
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(v.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (v.gender.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(v.gender, Modifier.clip(RoundedCornerShape(50)).background(scheme.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(listOf(v.place(), v.note).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (picked) Icon(Icons.Rounded.Check, "In use", tint = scheme.primary)
    }
}

/** The Microsoft key and region, folded into one line once they work. */
@Composable
private fun MicrosoftKey(engine: ReadAloud, error: String?, open: Boolean, onOpen: () -> Unit, onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var editing by remember(open) { mutableStateOf(open) }
    if (!editing) {
        TextButton(onClick = { editing = true; onOpen() }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Add Microsoft voices") }
        return
    }
    var key by remember { mutableStateOf(engine.prefs.msKey) }
    var region by remember { mutableStateOf(engine.prefs.msRegion.ifBlank { "eastus" }) }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        OutlinedTextField(key, { key = it }, label = { Text("Azure Speech key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(region, { region = it }, label = { Text("Region, like eastus") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = scheme.error, modifier = Modifier.padding(top = 6.dp))
        Text(
            "Make a free Speech resource in the Azure portal, then copy its key and region here. The free tier covers about half a million letters a month.",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (engine.prefs.msKey.isNotBlank()) TextButton(onClick = { engine.prefs.msKey = ""; engine.prefs.msRegion = ""; engine.refreshVoices(); editing = false; onDone() }) { Text("Remove") }
            TextButton(onClick = { engine.prefs.msKey = key; engine.prefs.msRegion = region; engine.refreshVoices(); editing = false; onDone() }, enabled = key.isNotBlank() && region.isNotBlank()) { Text("Save") }
        }
    }
}

/** Runs [block] each time the screen comes back, like after the phone's settings. */
@Composable
fun OnResume(block: () -> Unit) {
    val ctx = LocalContext.current
    val owner = remember {
        var c: android.content.Context = ctx
        while (c is android.content.ContextWrapper && c !is androidx.lifecycle.LifecycleOwner) c = c.baseContext
        c as? androidx.lifecycle.LifecycleOwner
    }
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) block() }
        owner?.lifecycle?.addObserver(o)
        onDispose { owner?.lifecycle?.removeObserver(o) }
    }
}

/** One saved item in the offline audio list. [load] reads its text when the audio is made. */
data class OfflineItem(val id: String, val title: String, val source: String, val load: suspend () -> ListenDoc?)

/**
 * Offline audio, like Readest's: every saved item with whether its audio is on the phone, partly, or not,
 * and a button to save or delete it.
 */
@Composable
fun OfflineAudioList(engine: ReadAloud, items: List<OfflineItem>, modifier: Modifier = Modifier) {
    val keeps by engine.keeps.collectAsState()
    val u by engine.ui.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val voice = engine.keepVoice("en-US")
    LaunchedEffect(items.size, voice?.id) { for (i in items) i.load()?.let { engine.refreshKeep(it) } }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (voice?.online == false) item {
            Text(
                "${voice.name} works with no internet, so nothing needs saving. Pick an online voice to save its audio.",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
            )
        }
        items(items, key = { it.id }) { it ->
            val k = keeps[it.id]
            val playing = u.active && u.docId == it.id
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (playing) { Icon(Icons.Rounded.GraphicEq, "Playing", Modifier.size(20.dp), tint = scheme.primary); Spacer(Modifier.width(8.dp)) }
                        Text(it.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val state = when {
                        k == null -> it.source
                        k.running -> "Saving ${k.done} of ${k.total}"
                        k.error != null -> k.error
                        k.complete -> "Saved"
                        k.partly -> "Partly saved"
                        else -> it.source
                    }
                    if (state.isNotBlank()) Text(state, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                if (voice?.online == true) when {
                    k?.running == true -> Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { if (k.total > 0) k.done.toFloat() / k.total else 0f }, modifier = Modifier.size(36.dp))
                        IconButton(onClick = { engine.stopKeep(it.id) }) { Icon(Icons.Rounded.Close, "Stop saving", Modifier.size(18.dp)) }
                    }
                    k?.complete == true -> IconButton(onClick = { engine.removeKept(it.id) }) { Icon(Icons.Rounded.Delete, "Delete the saved audio", tint = scheme.onSurfaceVariant) }
                    else -> IconButton(onClick = { scope.launch { it.load()?.let { d -> engine.keep(d) } } }) {
                        Icon(Icons.Rounded.Download, if (k?.partly == true) "Finish saving" else "Save the audio", tint = if (k?.partly == true) scheme.primary else scheme.onSurface)
                    }
                }
            }
        }
    }
}

/**
 * Read aloud settings, in short pages: the main page has four rows, and each opens its own page.
 * Default voice, speed, offline audio (with its saved list), and what the page does while it reads.
 */
@Composable
fun ListenSettings(engine: ReadAloud, items: List<OfflineItem>, onBack: () -> Unit) {
    var page by remember { mutableStateOf("main") }
    val u by engine.ui.collectAsState()
    val p = engine.prefs
    val scheme = MaterialTheme.colorScheme
    var keepWithSaves by remember { mutableStateOf(p.keepWithSaves) }
    var saveWithLast by remember { mutableStateOf(p.saveWithLast) }
    var wifiOnly by remember { mutableStateOf(p.wifiOnly) }
    var highlight by remember { mutableStateOf(p.highlightWords) }
    var follow by remember { mutableStateOf(p.followAlong) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun parent(of: String) = if (of == "saved") "offline" else "main"
    androidx.activity.compose.BackHandler(enabled = page != "main") { page = parent(page) }
    val mb = "%.1f MB".format(engine.keptBytes() / 1_048_576.0)
    Column(Modifier.fillMaxWidth()) {
        when (page) {
            "voices" -> VoicePicker(engine, "en-US", onBack = { page = "main" }, title = "Default voice", isDefault = true)
            "speed" -> {
                BackRow("Speed", { page = "main" })
                Column(Modifier.padding(horizontal = 24.dp)) {
                    var speed by remember(u.speed) { mutableFloatStateOf(u.speed) }
                    Text(speedLabel(speed), style = MaterialTheme.typography.displaySmall, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp))
                    Slider(value = speed, onValueChange = { speed = Math.round(it * 20) / 20f }, onValueChangeFinished = { engine.setSpeed(speed) }, valueRange = 0.5f..3f)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        for (x in listOf(0.75f, 1f, 1.25f, 1.5f, 2f)) FilterChip(selected = kotlin.math.abs(u.speed - x) < 0.01f, onClick = { engine.setSpeed(x) }, label = { Text(speedLabel(x)) })
                    }
                }
            }
            "offline" -> {
                BackRow("Offline audio", { page = "main" })
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                    SwitchRow("Save audio with each save", "Online voices only. Phone voices already work with no internet.", keepWithSaves) { keepWithSaves = it; p.keepWithSaves = it }
                    SettingsGroup("Save audio with")
                    PickRow("Default voice", !saveWithLast) { saveWithLast = false; p.saveWithLast = false }
                    PickRow("Last voice used", saveWithLast) { saveWithLast = true; p.saveWithLast = true }
                    Text("Now: ${engine.keepVoice("en-US")?.name ?: "Phone voice"}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                    SettingsGroup("Saving")
                    SwitchRow("Only on Wi-Fi", "Saving waits for Wi-Fi, so it uses no mobile data.", wifiOnly) { wifiOnly = it; p.wifiOnly = it }
                    SheetRow(Icons.Rounded.Download, "Saved audio", "${items.count { engine.keeps.value[it.id]?.complete == true }}", "$mb on this phone") { page = "saved" }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete all saved audio", color = scheme.error) }
                }
            }
            "saved" -> {
                BackRow("Saved audio", { page = "offline" }, subtitle = "$mb on this phone")
                OfflineAudioList(engine, items, Modifier.fillMaxWidth().weight(1f, fill = false))
            }
            "reading" -> {
                BackRow("While reading", { page = "main" })
                Column(Modifier.padding(horizontal = 20.dp)) {
                    SwitchRow("Light up each word", "The word being read is marked on the page.", highlight) { highlight = it; p.highlightWords = it }
                    SwitchRow("Follow along", "The page scrolls with the voice.", follow) { follow = it; p.followAlong = it }
                }
            }
            else -> {
                BackRow("Read aloud", onBack)
                Column(Modifier.padding(horizontal = 20.dp)) {
                    val def = engine.defaultVoice("en-US")
                    SheetRow(Icons.Rounded.RecordVoiceOver, "Default voice", def?.name ?: "Phone voice", def?.let { voiceDetail(it) }) { page = "voices" }
                    SheetRow(Icons.Rounded.Speed, "Speed", speedLabel(u.speed), null) { page = "speed" }
                    SheetRow(Icons.Rounded.Download, "Offline audio", if (keepWithSaves) "On" else "Off", "Save audio to listen with no internet · $mb") { page = "offline" }
                    SheetRow(Icons.Rounded.GraphicEq, "While reading", "", listOf(if (highlight) "Light up words" else null, if (follow) "follow along" else null).filterNotNull().joinToString(", ").ifBlank { "Nothing marked" }.replaceFirstChar { it.uppercase() }) { page = "reading" }
                }
            }
        }
    }
    if (confirmDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete all saved audio?") },
        text = { Text("Articles stay. Audio is made again when you listen.") },
        confirmButton = { TextButton(onClick = { engine.removeAllKept(); confirmDelete = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsGroup(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!on) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
