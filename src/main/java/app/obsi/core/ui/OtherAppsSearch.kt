package app.obsi.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsi.core.link.Links
import app.obsi.core.share.SharedHit
import app.obsi.core.share.SharedSearch
import kotlinx.coroutines.delay

/** What the other Obsi apps found for [query]. Asked a moment after typing stops, so each letter does not ask. */
@Composable
fun rememberOtherHits(query: String): List<SharedHit> {
    val context = LocalContext.current
    val hits by produceState(emptyList<SharedHit>(), query) {
        if (query.isBlank()) { value = emptyList(); return@produceState }
        delay(250)
        value = SharedSearch.fromOthers(context, query)
    }
    return hits
}

/** The other apps' results, under a heading, for the end of a search list. A tap opens the result in its app. */
fun LazyListScope.otherAppsResults(hits: List<SharedHit>, query: String, limit: Int = 20) {
    if (hits.isEmpty()) return
    item("obsi-others-head") {
        Text(
            "In your other apps", Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    items(hits.take(limit), key = { "obsi-" + it.app.name + it.kind + it.itemId + it.at + it.snippet.hashCode() }) { h ->
        val context = LocalContext.current
        SharedHitRow(h, query) { Links.openIn(context, h.app, h.itemId, h.at) }
    }
}

/** One result from another app: its app's icon, then the words, then where they are. */
@Composable
fun SharedHitRow(h: SharedHit, query: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(h.app.statColor().copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(h.app.icon, null, Modifier.size(20.dp), tint = h.app.statColor())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (h.snippet.isNotBlank()) {
                Row(verticalAlignment = Alignment.Top) {
                    if (h.color != 0L) Box(Modifier.padding(top = 4.dp, end = 8.dp).size(width = 3.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(Color(h.color)))
                    Text(marks(h.snippet, query, scheme.primary), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOf(h.app.title, h.title, h.subtitle).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Text(marks(h.title, query, scheme.primary), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(h.app.title, h.subtitle).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** [text] with each word of [query] in bold. */
private fun marks(text: String, query: String, color: Color) = buildAnnotatedString {
    append(text)
    val low = text.lowercase()
    query.lowercase().split(' ').filter { it.length > 1 }.forEach { w ->
        var i = low.indexOf(w)
        while (i >= 0) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color), i, i + w.length)
            i = low.indexOf(w, i + w.length)
        }
    }
}
