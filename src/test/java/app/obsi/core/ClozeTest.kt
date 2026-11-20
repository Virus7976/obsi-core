package app.obsi.core

import app.obsi.core.data.Cloze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClozeTest {
    private fun hidden(t: String) = Cloze.pick(t)?.let { t.substring(it.start, it.end) }

    @Test fun picksANameOrANumberOverCommonWords() {
        assertEquals("Marcus Aurelius", hidden("The emperor Marcus Aurelius wrote his notes only for himself."))
        assertEquals("10,000", hidden("You need about 10,000 steps a day to see the change."))
    }

    @Test fun neverHidesAStopWord() {
        val t = "There is something about this that they would really want to know about."
        val h = hidden(t)
        if (h != null) assert(h.lowercase() !in setOf("there", "something", "about", "would", "really", "want", "know")) { h }
    }

    @Test fun tooShortGetsNoBlank() {
        assertNull(Cloze.pick("Be kind."))
    }

    @Test fun findsTheAiPickOnlyWhenItIsReallyThere() {
        val t = "Sleep clears adenosine from the brain."
        assertNotNull(Cloze.find(t, "adenosine"))
        assertNull(Cloze.find(t, "dopamine"))
    }
}
