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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.unit.dp
import app.obsi.core.ai.Ai
import app.obsi.core.ai.Meaning
import app.obsi.core.link.Links
import kotlinx.coroutines.launch

/**
 * In search: one quiet row that finds your highlights and passages by what they mean, not only by
 * their words. Nothing is sent until you tap it. The matches are your own words, as you saved them,
 * and each opens where it is, in its own app.
 */
fun LazyListScope.findByMeaning(query: String) {
    if (query.trim().length < 3) return
    item(key = "find-by-meaning") { FindByMeaning(query) }
}

@Composable
private fun FindByMeaning(query: String) {
    val ctx = LocalContext.current
    remember { Ai.store(ctx) }
    val usable by Ai.usable.collectAsState()
    if (!usable) return
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var found by remember(query) { mutableStateOf(Meaning.cached(query)) }
    var busy by remember(query) { mutableStateOf(false) }
    var error by remember(query) { mutableStateOf<String?>(null) }
    fun run() {
        busy = true; error = null
        scope.launch {
            runCatching { Meaning.find(ctx, query) }.onSuccess { found = it }.onFailure { error = it.message ?: "Could not search. Try again." }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(22.dp)).background(scheme.surfaceContainer).animateContentSize()) {
        val f = found
        when {
            f != null -> {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (f.hits.isEmpty()) "Nothing you saved matches this idea yet" else "Matches the idea",
                        style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { Meaning.forget(query); found = null }) { Icon(Icons.Rounded.Close, "Close", Modifier.size(20.dp), tint = scheme.onSurfaceVariant) }
                }
                Column(Modifier.padding(bottom = 8.dp)) {
                    f.hits.forEach { h -> SharedHitRow(h, "") { Links.openIn(ctx, h.app, h.itemId, h.at) } }
                }
            }
            busy -> Looking()
            else -> Row(
                Modifier.fillMaxWidth().clickable { run() }.padding(horizontal = 18.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Find by meaning", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        error ?: "Your highlights and passages about this idea, even in other words",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error != null) scheme.error else scheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Find by meaning", tint = scheme.primary)
            }
        }
    }
}

/** Three soft lines while it looks: the shape of what is coming, no spinner. */
@Composable
private fun Looking() {
    val t = rememberInfiniteTransition(label = "lines")
    val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Looking through your library…", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        listOf(1f, 0.92f, 0.6f).forEach { w ->
            Box(Modifier.fillMaxWidth(w).height(12.dp).clip(RoundedCornerShape(6.dp)).alpha(a).background(scheme.surfaceContainerHighest))
        }
    }
}
