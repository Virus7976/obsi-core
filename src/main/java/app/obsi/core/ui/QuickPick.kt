package app.obsi.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsi.core.data.Omni

/** One choice in a [QuickPick]. [tag] is a short label on the right, like a price or "Free". */
data class PickItem(val id: String, val title: String, val subtitle: String = "", val tag: String = "")

/**
 * An Obsidian-style quick switcher for any list: type a few letters, skipped letters forgiven,
 * best match first. Every picker in the Obsi apps is one of these, never a plain dropdown.
 */
@Composable
fun QuickPick(
    title: String,
    items: List<PickItem>,
    selected: String?,
    onPick: (PickItem) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String = "Type to find",
    /** Shown when the list is empty, for example while it loads. */
    empty: String = "Nothing here yet.",
    /** Lets you pick what you typed when nothing matches, for example a model name the list does not show. */
    allowTyped: Boolean = false,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { sheet.awaitOpen(); runCatching { focus.requestFocus() } }
    val scheme = MaterialTheme.colorScheme
    val shown = remember(items, query) {
        val q = query.trim()
        if (q.isEmpty()) items.map { it to emptyList<Int>() }
        else items.mapNotNull { item ->
            val tokens = q.split(' ').filter { it.isNotEmpty() }
            var score = 0.0
            var at: List<Int> = emptyList()
            for (t in tokens) {
                val f = Omni.fuzzy(item.title, t) ?: Omni.fuzzy(item.id, t)?.let { Omni.Fuzzy(it.score * 0.8, emptyList()) }
                    ?: Omni.fuzzy(item.subtitle, t)?.let { Omni.Fuzzy(it.score * 0.5, emptyList()) } ?: return@mapNotNull null
                score += f.score
                if (tokens.size == 1) at = f.at
            }
            Triple(item, at, score)
        }.sortedByDescending { it.third }.map { it.first to it.second }
    }
    SmoothSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
                singleLine = true,
                placeholder = { Text(placeholder) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                shape = RoundedCornerShape(50),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    shown.firstOrNull()?.let { onPick(it.first) }
                        ?: if (allowTyped && query.isNotBlank()) onPick(PickItem(query.trim(), query.trim())) else Unit
                }),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                if (items.isEmpty()) item { Text(empty, Modifier.padding(24.dp), color = scheme.onSurfaceVariant) }
                else if (shown.isEmpty()) item {
                    if (allowTyped && query.isNotBlank()) PickRow(PickItem(query.trim(), "Use \"${query.trim()}\"", "Not in the list, but you can still use it"), emptyList(), false) { onPick(PickItem(query.trim(), query.trim())) }
                    else Text("Nothing matches \"${query.trim()}\".", Modifier.padding(24.dp), color = scheme.onSurfaceVariant)
                }
                items(shown, key = { it.first.id }) { (item, at) -> PickRow(item, at, item.id == selected) { onPick(item) } }
            }
        }
    }
}

@Composable
private fun PickRow(item: PickItem, at: List<Int>, picked: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 1.dp).clip(RoundedCornerShape(16.dp))
            .background(if (picked) scheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(marked(item.title, at, scheme.primary), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (item.tag.isNotBlank()) Text(item.tag, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
        if (picked) Icon(Icons.Rounded.Check, "Picked", tint = scheme.primary, modifier = Modifier.padding(start = 8.dp))
    }
}
