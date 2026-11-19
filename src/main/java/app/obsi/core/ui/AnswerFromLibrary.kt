package app.obsi.core.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsi.core.ai.Ai
import app.obsi.core.ai.Answer
import app.obsi.core.ai.Answers
import app.obsi.core.link.Links
import app.obsi.core.share.SharedHit
import kotlinx.coroutines.launch

/**
 * In search: when you type a question and an AI service is set up, one quiet row offers an answer
 * from what you saved. Nothing is sent until you tap it. The answer reads like a note, with its
 * sources numbered under it; each number opens the place it came from, in its own app.
 */
fun LazyListScope.answerFromLibrary(query: String) {
    if (!Answers.looksLikeQuestion(query)) return
    item(key = "answer-from-library") { AnswerFromLibrary(query) }
}

@Composable
private fun AnswerFromLibrary(query: String) {
    val ctx = LocalContext.current
    val ai by remember { Ai.store(ctx) }.state.collectAsState()
    if (!ai.ready) return
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var answer by remember(query) { mutableStateOf(Answers.cached(query)) }
    var busy by remember(query) { mutableStateOf(false) }
    var error by remember(query) { mutableStateOf<String?>(null) }
    fun run() {
        busy = true; error = null
        scope.launch {
            runCatching { Answers.ask(ctx, query) }.onSuccess { answer = it }.onFailure { error = it.message ?: "Could not answer that. Try again." }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(22.dp))
            .background(scheme.surfaceContainer).animateContentSize(),
    ) {
        val a = answer
        when {
            a != null -> AnswerBody(a, onAgain = { Answers.forget(query); answer = null; run() })
            busy -> Thinking()
            else -> Row(
                Modifier.fillMaxWidth().clickable { run() }.padding(horizontal = 18.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Answer from your library", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        error ?: "From your highlights, notes and transcripts in every Obsi app",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error != null) scheme.error else scheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Answer", tint = scheme.primary)
            }
        }
    }
}

@Composable
private fun AnswerBody(a: Answer, onAgain: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    fun open(h: SharedHit) = Links.openIn(ctx, h.app, h.itemId, h.at)
    Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("From your library", style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.weight(1f))
            IconButton(onClick = { app.obsi.core.obsidian.Sender.copy(ctx, a.text) }) { Icon(Icons.Rounded.ContentCopy, "Copy the answer", Modifier.size(18.dp), tint = scheme.onSurfaceVariant) }
            IconButton(onClick = onAgain) { Icon(Icons.Rounded.Refresh, "Answer again", Modifier.size(20.dp), tint = scheme.onSurfaceVariant) }
        }
        MarkdownText(a.text, Modifier.padding(end = 10.dp), onCite = { n -> a.sources.getOrNull(n - 1)?.let(::open) })
        if (a.sources.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Sources", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            a.sources.forEachIndexed { i, h -> SourceRow(i + 1, h) { open(h) } }
        }
    }
}

@Composable
private fun SourceRow(n: Int, h: SharedHit, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(end = 10.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = scheme.onPrimaryContainer) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(h.app.title, h.subtitle).filter { it.isNotBlank() }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Three soft lines while the answer is written: the shape of what is coming, no spinner. */
@Composable
private fun Thinking() {
    val t = rememberInfiniteTransition(label = "lines")
    val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Reading your library…", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        listOf(1f, 0.92f, 0.6f).forEach { w ->
            Box(Modifier.fillMaxWidth(w).height(12.dp).clip(RoundedCornerShape(6.dp)).alpha(a).background(scheme.surfaceContainerHighest))
        }
    }
}
