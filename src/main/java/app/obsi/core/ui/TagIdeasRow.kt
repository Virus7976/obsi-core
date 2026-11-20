package app.obsi.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.obsi.core.ai.Ai
import app.obsi.core.ai.TagIdeas
import kotlinx.coroutines.launch

/**
 * A few tags for [text], your own tags first; tap one to add it. Settings › AI service › Tag ideas
 * decides how: "By themselves" asks quietly as soon as this shows, "When I tap" shows a small
 * "Suggest tags" chip, and only while the tag field is [open]. Shows nothing with no AI service.
 */
@Composable
fun TagIdeasRow(text: String, known: List<String>, current: List<String>, onAdd: (String) -> Unit, modifier: Modifier = Modifier, open: Boolean = true) {
    val ctx = LocalContext.current
    val ai by remember { Ai.store(ctx) }.state.collectAsState()
    if (!ai.ready || text.isBlank()) return
    val auto = ai.tagIdeas != "ask"
    if (!auto && !open) return
    val scope = rememberCoroutineScope()
    var ideas by remember(text) { mutableStateOf(TagIdeas.cached(ctx, text)) }
    var busy by remember(text) { mutableStateOf(false) }
    var failed by remember(text) { mutableStateOf(false) }
    fun ask() {
        if (busy) return
        busy = true; failed = false
        scope.launch {
            runCatching { TagIdeas.suggest(ctx, text, known) }.onSuccess { ideas = it }.onFailure { failed = true }
            busy = false
        }
    }
    // By themselves: asked once, quietly. A failure waits for a tap, so it never retries on its own.
    LaunchedEffect(text, auto) { if (auto && ideas == null) ask() }
    val left = ideas?.filter { i -> current.none { it.equals(i, true) } }
    if (auto && left != null && left.isEmpty()) return
    if (auto && left == null && !failed && !busy) return

    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (left == null) item(key = "ask") {
            AssistChip(
                onClick = { ask() },
                label = { Text(if (failed) "Try again" else if (busy && auto) "Tag ideas" else "Suggest tags") },
                leadingIcon = if (busy) ({ CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) }) else null,
            )
        } else items(left, key = { "idea$it" }) { t ->
            FilterChip(
                selected = false, onClick = { onAdd(t) },
                label = { Text("#$t", color = if (known.any { it.equals(t, true) }) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary) },
            )
        }
    }
}
