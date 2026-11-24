package app.obsi.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsi.core.data.ArticleDoc

/** A heading in the text: its words, its level (1 is the biggest), and the first word under it. */
data class Heading(val text: String, val level: Int, val word: Int)

/** The headings of a page, in order. Only ones that start on a word are kept, so each one can be jumped to. */
fun headingsOf(built: ArticleDoc.Built?): List<Heading> {
    val d = built?.doc ?: return emptyList()
    return built.styles.filter { it.kind == "heading" }.map { h ->
        Heading(d.text.substring(h.range.first, h.range.last + 1).trim(), h.level, d.wordAtChar(h.range.first))
    }.filter { it.word >= 0 && it.text.isNotEmpty() }
}

/** How much is left to read, in a few words: "12 min read" at the top, "4 min left" on the way, "Finished" at the end. */
fun timeLeftLabel(progress: Float, words: Int): String {
    val minutes = (words + 229) / 230
    val left = (((1f - progress) * words).toInt() + 229) / 230
    return when {
        progress > 0.985f -> "Finished"
        progress < 0.01f -> "${minutes.coerceAtLeast(1)} min read"
        else -> "${left.coerceAtLeast(1)} min left"
    }
}

/**
 * The bar along the bottom of a page you read: highlights, how much is left (a tap opens the contents),
 * Listen, reading settings, and Clip. A thin line along its top shows how far through you are.
 * The Clip button is loud only when you marked something that is not in Obsidian yet.
 */
@Composable
fun ReadingBar(
    progress: Float,
    timeLeft: String,
    highlights: Int,
    onHighlights: () -> Unit,
    hasContents: Boolean,
    onContents: () -> Unit,
    hearing: Boolean,
    canListen: Boolean,
    onListen: () -> Unit,
    onReading: () -> Unit,
    clipped: Boolean,
    unsaved: Int,
    onClip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = modifier.fillMaxWidth(), color = scheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(scheme.outlineVariant.copy(alpha = 0.35f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).background(scheme.primary))
            }
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onHighlights) {
                    Icon(Icons.Rounded.BorderColor, "Highlights", Modifier.size(20.dp), tint = scheme.tertiary)
                    if (highlights > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text("$highlights", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (hasContents) TextButton(onClick = onContents) {
                        Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, "Contents", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(timeLeft, maxLines = 1, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    } else Text(timeLeft, maxLines = 1, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                }
                IconButton(onClick = onListen, enabled = canListen) {
                    Icon(
                        Icons.Rounded.Headphones, if (hearing) "Read aloud controls" else "Listen", Modifier.size(22.dp),
                        tint = if (hearing) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onReading) {
                    Icon(Icons.Rounded.TextFields, "Reading settings", Modifier.size(22.dp), tint = scheme.onSurfaceVariant)
                }
                val label = when { !clipped -> "Clip"; unsaved > 0 -> "Clip $unsaved new"; else -> "Clipped" }
                val icon = if (clipped && unsaved == 0) Icons.Rounded.Check else Icons.Rounded.Upload
                if (unsaved > 0) Button(onClick = onClip, shapes = ButtonDefaults.shapes()) {
                    Icon(icon, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, maxLines = 1)
                } else FilledTonalButton(onClick = onClip, shapes = ButtonDefaults.shapes()) {
                    Icon(icon, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, maxLines = 1)
                }
            }
        }
    }
}

/** The headings in a pull-up sheet. The one you are in ([here]) is marked, and a tap jumps to a heading. */
@Composable
fun ContentsSheet(heads: List<Heading>, here: Int, onDismiss: () -> Unit, onJump: (Heading) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    SmoothSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        val list = rememberLazyListState(initialFirstVisibleItemIndex = (here - 2).coerceAtLeast(0))
        Text("Contents", Modifier.padding(start = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge)
        // The smallest level in this text sits at the left edge, whatever number it has.
        val top = heads.minOfOrNull { it.level } ?: 1
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(heads) { i, h ->
                val on = i == here
                val big = h.level <= top
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (on) scheme.secondaryContainer else Color.Transparent)
                        .clickable { onJump(h) }
                        .heightIn(min = 48.dp)
                        .padding(start = (12 + (h.level - top).coerceIn(0, 3) * 20).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        h.text, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = if (big) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        color = if (on) scheme.onSecondaryContainer else if (big) scheme.onSurface else scheme.onSurfaceVariant,
                    )
                    if (on) Text("Here", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSecondaryContainer)
                }
            }
        }
    }
}
