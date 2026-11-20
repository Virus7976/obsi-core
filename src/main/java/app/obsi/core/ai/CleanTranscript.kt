package app.obsi.core.ai

import android.content.Context
import app.obsi.core.speech.Speech
import app.obsi.core.speech.Word

/**
 * Fixes a speech-to-text transcript with the text model: misheard words, stops and capitals. Only the
 * text goes out, never the sound, in pieces of a few hundred words, so it costs few tokens. Each word
 * keeps its time. A piece the model gets wrong, or that fails, stays as it was.
 */
object CleanTranscript {
    /** About how many words go in one ask. */
    const val PIECE = 350

    private const val SYSTEM =
        "You fix machine transcripts of spoken talks. The text has mistakes: misheard words, missing " +
            "full stops, commas and capitals. Rewrite it with those fixed. Use the topic and the words around a " +
            "mistake to find the word that was really said. Keep every sentence, in the same order, in the " +
            "speaker's own words. Do not shorten, sum up, explain, or add anything. Reply with the fixed text only."

    /** Cuts [words] into pieces of about [size] words, each ending at a sentence end when one is near. */
    fun pieces(words: List<Word>, size: Int = PIECE): List<List<Word>> {
        val out = ArrayList<List<Word>>()
        var i = 0
        while (i < words.size) {
            var end = minOf(i + size, words.size)
            if (end < words.size) {
                val stop = (end - 1 downTo maxOf(i + size / 2, end - 80)).firstOrNull { (words[it].text.lastOrNull() ?: ' ') in ".?!" }
                if (stop != null) end = stop + 1
            }
            out += words.subList(i, end)
            i = end
        }
        return out
    }

    /**
     * The model's answer for one piece, as words with times, or null when it looks wrong: a cleaned piece
     * has about as many words as the one sent.
     */
    fun merge(piece: List<Word>, answer: String?): List<Word>? {
        val parts = answer?.trim()?.removeSurrounding("\"")?.split(Regex("""\s+"""))?.filter { it.isNotEmpty() } ?: return null
        if (parts.isEmpty()) return null
        val ratio = parts.size.toDouble() / piece.size
        if (ratio < 0.8 || ratio > 1.25) return null
        val out = ArrayList<Word>()
        Speech.Aligner(piece).take(parts, piece.first().start, piece.last().end, out)
        return out
    }

    /** [words] cleaned. [topic] is the title, so names come out right. [onProgress] gets 0..1. */
    suspend fun run(context: Context, words: List<Word>, topic: String, onProgress: (Float) -> Unit = {}): List<Word> {
        val ps = pieces(words)
        val out = ArrayList<Word>(words.size)
        ps.forEachIndexed { n, p ->
            val text = p.joinToString(" ") { it.text }
            val user = (if (topic.isNotBlank()) "Topic: $topic\n\n" else "") + text
            val answer = runCatching { Ai.ask(context, SYSTEM, user, fast = true, maxTokens = p.size * 3 + 200) }.getOrNull()
            out += merge(p, answer) ?: p
            onProgress((n + 1f) / ps.size)
        }
        return out
    }
}
