package app.obsi.core.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsi.core.link.Links
import app.obsi.core.share.SharedHighlight
import app.obsi.core.share.SharedReview
import kotlinx.coroutines.launch

/**
 * Due highlights from any of the Obsi apps, one at a time. Keep, Not now and Drop go back to the
 * app that owns the highlight, so its schedule moves on and it never comes up twice.
 */
@Composable
fun ReviewDeck(items: List<SharedHighlight>, title: String, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var at by remember(items) { mutableStateOf(0) }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(if (at < items.size) "${items.size - at} ready" else "Done", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        val h = items.getOrNull(at)
        if (h == null) {
            Text("That is all for now. Kept ones come back later, skipped ones tomorrow.", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape)) { Surface(color = Color(h.color), modifier = Modifier.size(10.dp)) {} }
                    Spacer(Modifier.width(8.dp))
                    Icon(h.app.icon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        listOf(h.app.title, h.itemTitle).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(h.text, style = MaterialTheme.typography.bodyLarge)
                if (h.note.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(h.note, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    fun go(action: String) {
                        scope.launch { SharedReview.act(context, h, action) }
                        at++
                    }
                    OutlinedButton(onClick = { go("drop") }) { Text("Drop") }
                    TextButton(onClick = { go("later") }) { Text("Not now") }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = { go("keep") }) { Text("Keep") }
                }
                TextButton(onClick = { Links.openIn(context, h.app, h.itemId, h.at) }, modifier = Modifier.padding(top = 2.dp)) {
                    Text("Open in ${h.app.title}", color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The other apps' due highlights, for the end of a review. Nothing shows when none are due. */
@Composable
fun OtherAppsReview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items by produceState(emptyList<SharedHighlight>(), Unit) { value = SharedReview.fromOthers(context) }
    ReviewDeck(items, "From your other apps", modifier)
}

/** Every due highlight, this app's and the others', for a Home screen. */
@Composable
fun AllAppsReview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items by produceState(emptyList<SharedHighlight>(), Unit) { value = SharedReview.own() + SharedReview.fromOthers(context) }
    ReviewDeck(items, "Review", modifier)
}

/** How many highlights the other Obsi apps have due. Add it to this app's own count, since Review shows them all. */
@Composable
fun rememberOthersDue(): Int {
    val context = LocalContext.current
    val n by produceState(0, Unit) { value = SharedReview.fromOthers(context).size }
    return n
}
