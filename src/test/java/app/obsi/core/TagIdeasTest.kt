package app.obsi.core

import app.obsi.core.ai.TagIdeas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagIdeasTest {
    @Test fun newTagsAreOneWord() {
        // The tag Jake got: his own note, joined with dashes.
        assertFalse(TagIdeas.keep("live-your-own-life"))
        assertFalse(TagIdeas.keep("carl-jung"))
        assertTrue(TagIdeas.keep("jung"))
        assertTrue(TagIdeas.keep("individuality"))
        assertTrue(TagIdeas.keep("advice"))
    }

    @Test fun dropsFiller() {
        assertFalse(TagIdeas.keep("note"))
        assertFalse(TagIdeas.keep("life"))
        assertFalse(TagIdeas.keep(""))
    }

    @Test fun keepsTheirOwnTagsAlways() {
        assertTrue(TagIdeas.keep("note", listOf("note")))
        assertTrue(TagIdeas.keep("deep-work", listOf("deep-work")))
    }

    @Test fun usesTheirTagForTheSameIdea() {
        val mine = listOf("habits", "jung", "deep-work", "stoicism")
        assertEquals("habits", TagIdeas.theirs("habit", mine))
        assertEquals("jung", TagIdeas.theirs("carl-jung", mine))
        assertEquals("deep-work", TagIdeas.theirs("deepwork", mine))
        assertEquals("stoicism", TagIdeas.theirs("stoicism", mine))
        assertNull(TagIdeas.theirs("writing", mine))
        assertNull(TagIdeas.theirs("habit", emptyList()))
    }
}
