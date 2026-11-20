package app.obsi.core

import app.obsi.core.ai.CleanTranscript
import app.obsi.core.speech.Speech
import app.obsi.core.speech.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanTranscriptTest {
    private fun words(text: String, gap: Double = 0.5) =
        text.split(" ").mapIndexed { i, t -> Word(t, i * gap, i * gap + 0.4) }

    @Test fun fixedWordsKeepTheirTimes() {
        val raw = words("the mush room his a fun guy")
        val fixed = CleanTranscript.merge(raw, "The mushroom is a fungus guy.")!!
        assertEquals(listOf("The", "mushroom", "is", "a", "fungus", "guy."), fixed.map { it.text })
        // "The" and "a" and "guy" were heard right, so they keep their own times.
        assertEquals(0.0, fixed[0].start, 0.0)
        assertEquals(2.0, fixed[3].start, 0.0)
        assertEquals(3.0, fixed[5].start, 0.0)
        assertTrue(fixed.zipWithNext().all { (a, b) -> b.start >= a.start })
    }

    @Test fun anAnswerThatShortensOrRunsOnIsThrownAway() {
        val raw = words("one two three four five six seven eight nine ten")
        assertNull(CleanTranscript.merge(raw, "A list of numbers."))
        assertNull(CleanTranscript.merge(raw, (raw + raw).joinToString(" ") { it.text }))
        assertNull(CleanTranscript.merge(raw, null))
        assertNull(CleanTranscript.merge(raw, "   "))
    }

    @Test fun piecesEndAtASentenceAndLoseNothing() {
        val raw = (1..1000).map { i -> Word(if (i % 40 == 0) "w$i." else "w$i", i.toDouble(), i + 0.5) }
        val ps = CleanTranscript.pieces(raw)
        assertEquals(raw, ps.flatten())
        assertTrue(ps.dropLast(1).all { it.last().text.endsWith(".") })
        assertTrue(ps.all { it.size <= CleanTranscript.PIECE })
    }

    @Test fun cuesTurnBackIntoTheSameWords() {
        val raw = words("Good light makes a good photo. Look at where the light comes from.", 0.3)
        val back = Speech.words(Speech.cues(raw))
        assertEquals(raw.map { it.text }, back.map { it.text })
        assertEquals(raw.map { Math.round(it.start * 1000) / 1000.0 }, back.map { Math.round(it.start * 1000) / 1000.0 })
    }
}
