package app.obsi.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTimingsTest {
    @Test fun piecesKeepWholeSentencesAndOffsets() {
        val p = "One short. Two short! Three is a little longer than the others? Four."
        val parts = SpeechTimings.pieces(p, max = 30)
        assertTrue(parts.size >= 2)
        for ((at, t) in parts) assertEquals(t, p.substring(at, at + t.length))
        assertEquals(p.replace(" ", ""), parts.joinToString("") { it.second }.replace(" ", ""))
    }

    @Test fun aLongSentenceIsCutAtACommaOrSpace() {
        val p = (1..80).joinToString(" ") { "word$it," }
        val parts = SpeechTimings.pieces(p, max = 100)
        assertTrue(parts.all { it.second.length <= 220 })
        for ((at, t) in parts) assertEquals(t, p.substring(at, at + t.length))
    }

    @Test fun blankHasNoPieces() {
        assertEquals(emptyList<Pair<Int, String>>(), SpeechTimings.pieces("   "))
        assertEquals(emptyList<Pair<Int, String>>(), SpeechTimings.pieces(" — "))
    }

    @Test fun placeWordsFollowsTheText() {
        val t = "Hello, world. Hello again."
        val w = SpeechTimings.placeWords(t, listOf(0.0 to "Hello", 0.4 to "world", 1.0 to "Hello", 1.3 to "again"))
        assertEquals(listOf(0 until 5, 7 until 12, 14 until 19, 20 until 25), w.map { it.start until it.end })
    }

    @Test fun placeWordsSkipsWhatItCannotFind() {
        val w = SpeechTimings.placeWords("It costs 5 dollars.", listOf(0.0 to "It", 0.2 to "costs", 0.5 to "five", 0.8 to "dollars"))
        assertEquals(listOf("It", "costs", "dollars"), w.map { "It costs 5 dollars.".substring(it.start, it.end) })
    }

    @Test fun wordAtFindsTheSpokenWord() {
        val w = listOf(WordTime(0.0, 0, 3), WordTime(0.5, 4, 8), WordTime(1.0, 9, 12))
        assertEquals(null, SpeechTimings.wordAt(w, -0.1))
        assertEquals(4, SpeechTimings.wordAt(w, 0.7)?.start)
        assertEquals(9, SpeechTimings.wordAt(w, 5.0)?.start)
    }


}
