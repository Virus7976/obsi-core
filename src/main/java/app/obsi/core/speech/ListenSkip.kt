package app.obsi.core.speech

import app.obsi.core.data.ArticleDoc

/**
 * A hint about part of one paragraph of a [ListenDoc]: what kind of text it is, so read aloud can skip it.
 * [start] and [end] are character offsets in that paragraph. [level] is a heading's level (1 to 6).
 */
data class ListenMark(val para: Int, val start: Int, val end: Int, val kind: String, val level: Int = 0)

/** The parts of a page that read aloud can leave out. Each is a switch in Settings, Read aloud, Skip. */
enum class SkipPart(val key: String, val label: String, val about: String, val default: Boolean) {
    SOURCES("sources", "Sources", "References, notes, see also and further reading at the end.", true),
    NOTES("notes", "Footnote numbers", "The small numbers and [1] marks after words.", true),
    LINKS("links", "Web addresses", "Links typed out in the text, like https://…", true),
    CAPTIONS("captions", "Picture captions", "The text under pictures.", true),
    TABLES("tables", "Tables", "Rows of a table.", true),
    CODE("code", "Code", "Blocks of computer code.", true),
    BRACKETS("brackets", "Text in brackets", "Asides in ( ) and [ ], like (born 1950).", false),
}

/**
 * Takes out what the reader asked to skip. Every paragraph keeps its length: a skipped part becomes spaces,
 * so each word read aloud still lines up with the word lit on the page. A paragraph that is all skipped
 * becomes blank and is not read.
 */
object ListenSkip {
    /** Headings that start a sources section. A section ends at the next heading of the same or a higher level. */
    private val sourceHeadings = Regex(
        "^(references?|notes?|footnotes?|endnotes?|citations?|sources?|bibliography|works cited|cited works|literature cited|" +
            "general references|further reading|external links|see also|related articles|related posts|related stories|read more|more from .*|" +
            "notes and references|references and notes|notes and sources|sources and notes)$",
        RegexOption.IGNORE_CASE,
    )

    /** "[12]", "[a]", "[citation needed]": the marks Wikipedia puts after words. */
    private val noteMarks = Regex("\\[(\\d{1,4}|[a-z]{1,2}|note \\d+|nb \\d+|citation needed|clarification needed|when\\?|who\\?|according to whom\\?|by whom\\?|which\\?|verification needed|dubious[^\\]]*|page needed|failed verification|better source needed)]", RegexOption.IGNORE_CASE)
    private val webAddress = Regex("(https?://|www\\.)[^\\s)\\]]+", RegexOption.IGNORE_CASE)
    private val roundBrackets = Regex("\\([^()]*\\)")
    private val squareBrackets = Regex("\\[[^\\[\\]]*]")
    /** Marks with no words: a bullet, a back link, a scene break. Never worth saying. */
    private val noWords = Regex("[•↩↑]|· · ·")

    fun isSourceHeading(text: String) = sourceHeadings.matches(text.trim().trimEnd(':', '.').replace(Regex("\\s+"), " "))

    /** A list entry that points back up into the text: a footnote or a reference. */
    fun isNoteEntry(text: String): Boolean {
        val t = text.trim()
        return t.endsWith("↩") || t.endsWith("↩︎") || t.startsWith("^ ") || t.startsWith("↑ ")
    }

    fun apply(d: ListenDoc, skip: Set<String>): List<String> {
        val byPara = d.marks.groupBy { it.para }
        var cutLevel = 0
        return d.paras.mapIndexed { i, text ->
            val marks = byPara[i].orEmpty()
            val heading = marks.firstOrNull { it.kind == "heading" }
            if (heading != null) {
                val level = heading.level.coerceIn(1, 6)
                if (cutLevel > 0 && level <= cutLevel) cutLevel = 0
                if (SkipPart.SOURCES.key in skip && isSourceHeading(text)) cutLevel = level
            }
            val whole = when {
                cutLevel > 0 -> true
                SkipPart.SOURCES.key in skip && isNoteEntry(text) -> true
                SkipPart.CAPTIONS.key in skip && marks.any { it.kind == "caption" } -> true
                SkipPart.CODE.key in skip && marks.any { it.kind == "code" } -> true
                SkipPart.TABLES.key in skip && marks.any { it.kind == "table" } -> true
                else -> false
            }
            if (whole) return@mapIndexed " ".repeat(text.length)
            val c = text.toCharArray()
            fun blank(a: Int, b: Int) { for (k in a.coerceAtLeast(0) until b.coerceAtMost(c.size)) if (c[k] != '\n') c[k] = ' ' }
            fun blank(r: Regex) = r.findAll(String(c)).forEach { blank(it.range.first, it.range.last + 1) }
            if (SkipPart.NOTES.key in skip) {
                marks.filter { it.kind == "note" }.forEach { blank(it.start, it.end) }
                blank(noteMarks)
            }
            if (SkipPart.LINKS.key in skip) blank(webAddress)
            if (SkipPart.BRACKETS.key in skip) { blank(roundBrackets); blank(squareBrackets) }
            blank(noWords)
            String(c)
        }
    }

    /** Hints for an article page: headings, captions, code, tables and footnote numbers, per paragraph. */
    fun marksOf(built: ArticleDoc.Built): List<ListenMark> {
        val paras = built.doc.paras
        if (paras.isEmpty()) return emptyList()
        val starts = paras.map { it.charStart }
        fun paraAt(pos: Int): Int {
            var i = starts.binarySearch(pos)
            if (i < 0) i = -i - 2
            return i.takeIf { it in paras.indices && pos < paras[it].charEnd } ?: -1
        }
        val out = ArrayList<ListenMark>()
        for (s in built.styles) {
            val p = paraAt(s.range.first)
            if (p < 0) continue
            val base = paras[p].charStart
            val a = s.range.first - base
            val b = minOf(s.range.last + 1, paras[p].charEnd) - base
            when (s.kind) {
                "heading" -> out += ListenMark(p, a, b, "heading", s.level)
                "caption" -> out += ListenMark(p, a, b, "caption")
                "pre" -> out += ListenMark(p, a, b, "code")
                // Only a short raised mark is a footnote number. A raised word, like the "th" in 5th, is read.
                "sup" -> {
                    val t = built.doc.text.substring(s.range.first, minOf(s.range.last + 1, built.doc.text.length)).trim()
                    if (t.length <= 4 && (t.any { it.isDigit() } || t.startsWith("["))) out += ListenMark(p, a, b, "note")
                }
                // Table cells are joined by a "  |  " divider.
                "bullet" -> if (built.doc.text.substring(s.range.first, minOf(s.range.last + 1, built.doc.text.length)).contains('|')) out += ListenMark(p, 0, 0, "table")
            }
        }
        return out.distinct()
    }

    /** The read-aloud document for an article page, with its hints. */
    fun ofArticle(id: String, title: String, source: String, built: ArticleDoc.Built, locale: String = "en-US"): ListenDoc {
        val d = built.doc
        return ListenDoc(
            id, title, source,
            d.paras.map { p -> d.text.substring(p.charStart.coerceIn(0, d.text.length), p.charEnd.coerceIn(0, d.text.length)) },
            locale, marksOf(built), d.paras.map { it.firstWord },
        )
    }
}
