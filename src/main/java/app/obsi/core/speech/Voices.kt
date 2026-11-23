package app.obsi.core.speech

import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile

/** Where a voice comes from. */
enum class VoiceKind {
    /** The phone's own voice, installed on the phone. Works with no internet. */
    PHONE,
    /** A phone voice that needs the internet (Google's online voices). Kept once made, so it plays offline later. */
    PHONE_ONLINE,
    /** Microsoft's neural voices, through the person's own free Azure key. Kept once made, so it plays offline later. */
    MICROSOFT,
    /** Microsoft Edge's free online voices, no key. Kept once made, so it plays offline later. */
    EDGE,
}

/**
 * A voice to read with. [id] is "phone:<name>" or "ms:<ShortName>".
 * [note] is a few extra words, like "Multilingual".
 */
@Serializable
data class SpeakerVoice(
    val id: String,
    val name: String,
    val locale: String,
    val gender: String = "",
    val kind: VoiceKind = VoiceKind.PHONE,
    val note: String = "",
) {
    val online get() = kind != VoiceKind.PHONE
    val language get() = locale.substringBefore('-').substringBefore('_').lowercase()

    /** "English (United States)", from the locale. */
    fun place(): String = runCatching {
        val l = java.util.Locale.forLanguageTag(locale.replace('_', '-'))
        if (l.displayCountry.isBlank()) l.displayLanguage else "${l.displayLanguage} (${l.displayCountry})"
    }.getOrDefault(locale)
}

/** When a word starts in one spoken piece of text: [start]..[end] are character offsets in that text. */
@Serializable
data class WordTime(val at: Double, val start: Int, val end: Int)

/** Pure timing maths for the word highlight. No Android. */
object SpeechTimings {
    /** The word spoken at [t] seconds, or null before the first word. */
    fun wordAt(words: List<WordTime>, t: Double): WordTime? {
        if (words.isEmpty() || t < words[0].at) return null
        var lo = 0
        var hi = words.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (words[mid].at <= t) lo = mid else hi = mid - 1
        }
        return words[lo]
    }

    /**
     * Word times spread over [duration] by length, for a voice that gives no word events.
     * Each word gets time in proportion to its letters, plus a little for the pause after it.
     */
    fun estimateWords(text: String, duration: Double): List<WordTime> {
        val spans = Regex("\\S+").findAll(text).map { it.range.first to it.range.last + 1 }.toList()
        if (spans.isEmpty() || duration <= 0) return emptyList()
        val weights = spans.map { (s, e) -> (e - s) + 1.5 + if (text[e - 1] in ",;:.!?") 3.0 else 0.0 }
        val total = weights.sum()
        var t = 0.0
        return spans.mapIndexed { i, (s, e) -> WordTime(t, s, e).also { t += duration * weights[i] / total } }
    }

    /**
     * Finds each spoken word in [text], in order, so the highlight lands on the right letters.
     * A word that cannot be found (the voice said a number another way) is skipped.
     */
    fun placeWords(text: String, spoken: List<Pair<Double, String>>): List<WordTime> {
        val out = ArrayList<WordTime>()
        var from = 0
        for ((at, w) in spoken) {
            val word = w.trim()
            if (word.isEmpty()) continue
            val i = text.indexOf(word, from, ignoreCase = true)
            if (i < 0 || i - from > 80) continue
            out += WordTime(at, i, i + word.length)
            from = i + word.length
        }
        return out
    }

    /**
     * Turns one phone word event into a word time. The docs give (start, end, frame), but Google's engine
     * was seen sending (frame, start, end). The two numbers that make a range inside the text are the
     * word; the other one is the audio frame.
     */
    fun phoneWord(t: Triple<Int, Int, Int>, textLength: Int, rate: Int): WordTime? {
        val (a, b, c) = t
        fun range(s: Int, e: Int) = s in 0 until e && e <= textLength
        return when {
            range(b, c) && !range(a, b) -> WordTime(a.toDouble() / rate, b, c)
            range(a, b) && !range(b, c) -> WordTime(c.toDouble() / rate, a, b)
            range(a, b) -> WordTime(c.toDouble() / rate, a, b)
            range(b, c) -> WordTime(a.toDouble() / rate, b, c)
            else -> null
        }
    }

    /**
     * Cuts a paragraph into pieces a voice can say in one go: whole sentences, joined up to about [max]
     * letters. Returns each piece's start offset and text, so a word in a piece maps back to the paragraph.
     */
    fun pieces(para: String, max: Int = 360): List<Pair<Int, String>> {
        if (para.isBlank()) return emptyList()
        val ends = Regex("(?<=[.!?…][\"'”’)\\]]?)\\s+").findAll(para).map { it.range.last + 1 }.toList()
        val bounds = (listOf(0) + ends + para.length).distinct().sorted()
        val out = ArrayList<Pair<Int, String>>()
        var start = 0
        var i = 1
        while (start < para.length) {
            var end = bounds.getOrNull(i) ?: para.length
            // Join short sentences up to the limit.
            while (i + 1 < bounds.size && bounds[i + 1] - start <= max) { i++; end = bounds[i] }
            // One sentence longer than the limit is cut at a comma or a space.
            if (end - start > max * 2) {
                val window = para.substring(start, start + max * 2)
                val cut = window.lastIndexOfAny(charArrayOf(',', ';', ':')).takeIf { it > max / 2 }
                    ?: window.lastIndexOf(' ').takeIf { it > max / 2 } ?: (max * 2 - 1)
                end = start + cut + 1
            } else i++
            val raw = para.substring(start, end)
            val lead = raw.length - raw.trimStart().length
            val text = raw.trim()
            if (text.any { it.isLetterOrDigit() }) out += (start + lead) to text
            start = end
            while (i < bounds.size && bounds[i] <= start) i++
        }
        return out
    }
}

/** Reads the size facts out of a WAV header. */
object Wav {
    data class Header(val rate: Int, val channels: Int, val bits: Int, val seconds: Double, val dataStart: Long)

    fun read(f: File): Header? = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val b = ByteArray(12)
            r.readFully(b)
            if (String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return null
            var rate = 0; var ch = 1; var bits = 16
            // Walk the chunks: "fmt " has the format, "data" has the sound.
            while (r.filePointer + 8 <= r.length()) {
                val h = ByteArray(8)
                r.readFully(h)
                val id = String(h, 0, 4)
                val size = (h[4].toLong() and 0xff) or ((h[5].toLong() and 0xff) shl 8) or ((h[6].toLong() and 0xff) shl 16) or ((h[7].toLong() and 0xff) shl 24)
                if (id == "fmt ") {
                    val f2 = ByteArray(16)
                    r.readFully(f2)
                    fun le16(i: Int) = (f2[i].toInt() and 0xff) or ((f2[i + 1].toInt() and 0xff) shl 8)
                    ch = le16(2).coerceAtLeast(1)
                    rate = le16(4) or (le16(6) shl 16)
                    bits = le16(14).coerceAtLeast(8)
                    r.seek(r.filePointer + size - 16)
                } else if (id == "data") {
                    val start = r.filePointer
                    val bytes = minOf(size, r.length() - start).coerceAtLeast(0)
                    return Header(rate, ch, bits, if (rate > 0) bytes.toDouble() / (rate * ch * (bits / 8)) else 0.0, start)
                } else r.seek(r.filePointer + size + (size and 1))
            }
            null
        }
    }.getOrNull()
}
