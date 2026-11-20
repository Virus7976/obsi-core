package app.obsi.core

import app.obsi.core.ai.TagIdeas
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagIdeasTest {
    private val quote = "Carl Jung understood this with unusual clarity. He once observed that the shoe that fits one person pinches another."
    private val note = "live your own life"

    @Test fun dropsCopiedPhrases() {
        // The tag Jake got: his own note, joined with dashes.
        assertFalse(TagIdeas.keep("live-your-own-life", quote, note))
        assertFalse(TagIdeas.keep("the-shoe-that", quote, note))
        assertFalse(TagIdeas.keep("unusual-clarity-here-now", quote, note))
    }

    @Test fun dropsFiller() {
        assertFalse(TagIdeas.keep("note", quote, note))
        assertFalse(TagIdeas.keep("life", quote, note))
        assertFalse(TagIdeas.keep("", quote, note))
    }

    @Test fun keepsTopics() {
        assertTrue(TagIdeas.keep("carl-jung", quote, note))
        assertTrue(TagIdeas.keep("individuality", quote, note))
        assertTrue(TagIdeas.keep("conformity", quote, note))
        assertTrue(TagIdeas.keep("advice", quote, note))
        // Three words that are not in the text are a real topic.
        assertTrue(TagIdeas.keep("philosophy-of-mind", quote, note))
    }

    @Test fun keepsTheirOwnTagsAlways() {
        assertTrue(TagIdeas.keep("note", quote, note, listOf("note")))
        assertTrue(TagIdeas.keep("live-your-own-life", quote, note, listOf("live-your-own-life")))
    }
}
