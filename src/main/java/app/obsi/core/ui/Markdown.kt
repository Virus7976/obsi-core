package app.obsi.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Markdown for people to read, not a text box: headings, lists, quotes, Obsidian callouts, code, rules,
 * bold, italic, ==marks==, links and [[wiki links]]. Shared by every Obsi app, for release notes,
 * AI answers and summaries. Numbers in brackets, like [2], become small source chips when [onCite] is set.
 */
object Md {
    sealed interface Block
    data class Heading(val level: Int, val text: String) : Block
    data class Para(val text: String) : Block
    data class Bullets(val items: List<Item>) : Block
    data class Numbered(val items: List<Item>, val start: Int) : Block
    data class Item(val text: String, val depth: Int, val checked: Boolean? = null)
    data class Quote(val text: String) : Block
    data class Callout(val kind: String, val title: String, val body: String) : Block
    data class Code(val text: String) : Block
    data class Table(val head: List<String>, val rows: List<List<String>>) : Block
    data object Rule : Block

    fun parse(text: String): List<Block> {
        val lines = text.replace("\r", "").split('\n')
        val out = ArrayList<Block>()
        var i = 0
        val para = ArrayList<String>()
        fun flush() { if (para.isNotEmpty()) { out += Para(para.joinToString(" ")); para.clear() } }
        while (i < lines.size) {
            val raw = lines[i]
            val t = raw.trim()
            when {
                t.isEmpty() -> { flush(); i++ }
                t.startsWith("```") -> {
                    flush()
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code += lines[i]; i++ }
                    i++
                    out += Code(code.joinToString("\n"))
                }
                t == "---" || t == "***" || t == "___" -> { flush(); out += Rule; i++ }
                t.startsWith("#") && t.trimStart('#').startsWith(" ") -> {
                    flush()
                    val level = t.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    out += Heading(level, t.drop(level).trim())
                    i++
                }
                t.startsWith(">") -> {
                    flush()
                    val body = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { body += lines[i].trim().removePrefix(">").removePrefix(" "); i++ }
                    val first = body.firstOrNull().orEmpty()
                    if (first.startsWith("[!") && first.contains(']')) {
                        val kind = first.substring(2, first.indexOf(']')).lowercase().trimEnd('-', '+')
                        val title = first.substringAfter(']').trimStart('-', '+').trim()
                        out += Callout(kind, title.ifBlank { kind.replaceFirstChar { it.uppercase() } }, body.drop(1).joinToString("\n"))
                    } else out += Quote(body.joinToString("\n"))
                }
                t.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().let { it.startsWith("|") && it.all { c -> c in "|:- " } } -> {
                    flush()
                    fun cells(s: String) = s.trim().trim('|').split('|').map { it.trim() }
                    val head = cells(t)
                    i += 2
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i]); i++ }
                    out += Table(head, rows)
                }
                bullet(raw) != null -> {
                    flush()
                    val items = ArrayList<Item>()
                    while (i < lines.size) {
                        val b = bullet(lines[i]) ?: break
                        items += b; i++
                        // A wrapped line belongs to the item above.
                        while (i < lines.size && lines[i].isNotBlank() && bullet(lines[i]) == null && number(lines[i]) == null && lines[i].startsWith("  ")) {
                            items[items.lastIndex] = items.last().copy(text = items.last().text + " " + lines[i].trim()); i++
                        }
                    }
                    out += Bullets(items)
                }
                number(raw) != null -> {
                    flush()
                    val start = number(raw)!!.first
                    val items = ArrayList<Item>()
                    while (i < lines.size) {
                        val n = number(lines[i]) ?: break
                        items += n.second; i++
                    }
                    out += Numbered(items, start)
                }
                else -> { para += t; i++ }
            }
        }
        flush()
        return out
    }

    private fun bullet(line: String): Item? {
        val depth = line.takeWhile { it == ' ' || it == '\t' }.let { s -> s.count { it == '\t' } * 2 + s.count { it == ' ' } } / 2
        val t = line.trimStart()
        if (t.length < 2 || t[0] !in "-*+" || t[1] != ' ') return null
        var rest = t.drop(2)
        var checked: Boolean? = null
        if (rest.startsWith("[ ] ")) { checked = false; rest = rest.drop(4) }
        else if (rest.startsWith("[x] ", true)) { checked = true; rest = rest.drop(4) }
        return Item(rest, depth.coerceAtMost(3), checked)
    }

    private fun number(line: String): Pair<Int, Item>? {
        val depth = line.takeWhile { it == ' ' }.length / 2
        val t = line.trimStart()
        val digits = t.takeWhile { it.isDigit() }
        if (digits.isEmpty() || digits.length > 3) return null
        val after = t.drop(digits.length)
        if (!(after.startsWith(". ") || after.startsWith(") "))) return null
        return digits.toInt() to Item(after.drop(2), depth.coerceAtMost(3))
    }

    /** Text with its marks turned into styles. Links open, citations call [onCite]. */
    fun inline(
        text: String,
        link: Color,
        mark: Color,
        codeBg: Color,
        chipBg: Color,
        chipFg: Color,
        onCite: ((Int) -> Unit)?,
        onLink: (String) -> Unit,
    ): AnnotatedString = buildAnnotatedString {
        var i = 0
        val s = text
        fun findClose(token: String, from: Int): Int = s.indexOf(token, from).takeIf { it > from } ?: -1
        while (i < s.length) {
            val c = s[i]
            when {
                s.startsWith("**", i) && findClose("**", i + 2) > 0 -> {
                    val end = findClose("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(s.substring(i + 2, end), link, mark, codeBg, chipBg, chipFg, onCite, onLink)) }
                    i = end + 2
                }
                s.startsWith("==", i) && findClose("==", i + 2) > 0 -> {
                    val end = findClose("==", i + 2)
                    withStyle(SpanStyle(background = mark)) { append(s.substring(i + 2, end)) }
                    i = end + 2
                }
                (c == '*' || c == '_') && i + 1 < s.length && s[i + 1] != ' ' && s[i + 1] != c &&
                    (i == 0 || !s[i - 1].isLetterOrDigit()) && findClose(c.toString(), i + 1) > 0 -> {
                    val end = findClose(c.toString(), i + 1)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                }
                c == '`' && findClose("`", i + 1) > 0 -> {
                    val end = findClose("`", i + 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 0.92.em())) { append(" " + s.substring(i + 1, end) + " ") }
                    i = end + 1
                }
                s.startsWith("[[", i) && s.indexOf("]]", i + 2) > 0 -> {
                    val end = s.indexOf("]]", i + 2)
                    val inner = s.substring(i + 2, end)
                    val shown = inner.substringAfter('|').substringBefore('#').ifBlank { inner }
                    withStyle(SpanStyle(color = link, fontWeight = FontWeight.Medium)) { append(shown) }
                    i = end + 2
                }
                c == '[' && onCite != null && citation(s, i) != null -> {
                    val (nums, end) = citation(s, i)!!
                    nums.forEachIndexed { k, n ->
                        if (k > 0) append(" ")
                        pushLink(LinkAnnotation.Clickable("cite$n", TextLinkStyles(SpanStyle(background = chipBg, color = chipFg, fontWeight = FontWeight.SemiBold, fontSize = 0.78.em()))) { onCite(n) })
                        append(" $n ")
                        pop()
                    }
                    i = end
                }
                c == '[' && s.indexOf("](", i) > 0 && s.indexOf(')', s.indexOf("](", i)) > 0 && !s.substring(i + 1, s.indexOf("](", i)).contains('[') -> {
                    val mid = s.indexOf("](", i)
                    val end = s.indexOf(')', mid)
                    val label = s.substring(i + 1, mid)
                    val url = s.substring(mid + 2, end)
                    pushLink(LinkAnnotation.Clickable(url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))) { onLink(url) })
                    append(label)
                    pop()
                    i = end + 1
                }
                else -> { append(c); i++ }
            }
        }
    }

    /** "[1]" or "[1, 3]" at [at]: the numbers and where it ends. */
    fun citation(s: String, at: Int): Pair<List<Int>, Int>? {
        val end = s.indexOf(']', at)
        if (end < 0 || end - at > 16) return null
        if (end + 1 < s.length && s[end + 1] == '(') return null
        val nums = s.substring(at + 1, end).split(',').map { it.trim().toIntOrNull() ?: return null }
        if (nums.isEmpty() || nums.any { it < 1 || it > 99 }) return null
        return nums to end + 1
    }

    private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)
}

/** Draws markdown cleanly. [onCite] turns [1] into a tappable source chip. */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    onCite: ((Int) -> Unit)? = null,
    onLink: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val uri = LocalUriHandler.current
    val blocks = remember(text) { Md.parse(text) }
    val open: (String) -> Unit = onLink ?: { u -> runCatching { uri.openUri(u) } }
    fun ann(s: String) = Md.inline(
        s, scheme.primary, scheme.tertiaryContainer, scheme.surfaceContainerHighest,
        scheme.primaryContainer, scheme.onPrimaryContainer, onCite, open,
    )
    val body = type.bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, b ->
            when (b) {
                is Md.Heading -> {
                    if (index > 0) Spacer(Modifier.height(if (b.level <= 2) 8.dp else 2.dp))
                    Text(
                        ann(b.text),
                        style = when (b.level) {
                            1 -> type.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                            2 -> type.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                            3 -> type.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            else -> type.titleSmall.copy(fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant)
                        },
                    )
                }
                is Md.Para -> Text(ann(b.text), style = body)
                is Md.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.items.forEach { item ->
                        Row(Modifier.padding(start = (item.depth * 18).dp)) {
                            Box(Modifier.width(20.dp).height(23.dp), contentAlignment = Alignment.Center) {
                                when (item.checked) {
                                    null -> Box(Modifier.size(if (item.depth == 0) 6.dp else 5.dp).clip(RoundedCornerShape(50)).background(if (item.depth == 0) scheme.primary else scheme.outline))
                                    true -> Text("✓", color = scheme.primary, style = type.titleSmall)
                                    false -> Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(scheme.outlineVariant))
                                }
                            }
                            Text(ann(item.text), style = body, modifier = Modifier.weight(1f))
                        }
                    }
                }
                is Md.Numbered -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.items.forEachIndexed { k, item ->
                        Row(Modifier.padding(start = (item.depth * 18).dp)) {
                            Text("${b.start + k}.", style = body.copy(color = scheme.primary, fontWeight = FontWeight.SemiBold), modifier = Modifier.widthIn(min = 26.dp))
                            Text(ann(item.text), style = body, modifier = Modifier.weight(1f))
                        }
                    }
                }
                is Md.Quote -> Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).clip(RoundedCornerShape(4.dp))) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(scheme.primary))
                    Column(Modifier.background(scheme.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 10.dp).weight(1f)) {
                        MarkdownText(b.text, onCite = onCite, onLink = onLink)
                    }
                }
                is Md.Callout -> {
                    val (bg, fg) = calloutColors(b.kind, scheme)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(ann(b.title), style = type.titleSmall.copy(fontWeight = FontWeight.SemiBold, color = fg))
                        if (b.body.isNotBlank()) MarkdownText(b.body, onCite = onCite, onLink = onLink)
                    }
                }
                is Md.Code -> Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHighest)
                        .horizontalScroll(rememberScrollState()).padding(12.dp),
                ) { Text(b.text, style = type.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
                is Md.Table -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerLow).horizontalScroll(rememberScrollState()),
                ) {
                    Row(Modifier.background(scheme.surfaceContainerHigh)) { b.head.forEach { h -> Text(ann(h), style = type.labelLarge, modifier = Modifier.widthIn(min = 96.dp, max = 220.dp).padding(10.dp)) } }
                    b.rows.forEach { r -> Row { r.forEach { c -> Text(ann(c), style = type.bodyMedium, modifier = Modifier.widthIn(min = 96.dp, max = 220.dp).padding(10.dp)) } } }
                }
                Md.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant)
            }
        }
    }
}

private fun calloutColors(kind: String, s: androidx.compose.material3.ColorScheme): Pair<Color, Color> = when (kind) {
    "warning", "caution", "attention", "danger", "error", "bug", "failure" -> s.errorContainer to s.onErrorContainer
    "tip", "hint", "important", "success", "check", "done" -> s.tertiaryContainer to s.onTertiaryContainer
    "quote", "cite" -> s.surfaceContainerHigh to s.onSurfaceVariant
    else -> s.secondaryContainer to s.onSecondaryContainer
}
