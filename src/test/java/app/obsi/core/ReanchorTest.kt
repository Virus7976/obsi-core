package app.obsi.core

import app.obsi.core.data.Highlight
import app.obsi.core.data.Highlights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReanchorTest {
    private fun words(s: String) = s.split(" ")
    private fun h(text: String, w0: Int, w1: Int) = Highlight("h", 0.0, 0.0, text, w0 = w0, w1 = w1)

    @Test
    fun aHighlightFollowsItsWordsWhenTextBeforeItIsTakenOut() {
        val before = words("Copyright notice here. The light is soft. Copyright notice here. Hard light at noon.")
        val after = words("The light is soft. Hard light at noon.")
        val old = h("Hard light at noon.", 10, 13)
        assertEquals("Hard", before[10])
        val moved = Highlights.reanchor(listOf(old), after)!!.single()
        assertEquals(4, moved.w0)
        assertEquals(7, moved.w1)
    }

    @Test
    fun nothingMovesWhenTheWordsStillMatch() {
        val w = words("The light is soft. Hard light at noon.")
        assertNull(Highlights.reanchor(listOf(h("Hard light", 4, 5)), w))
    }

    @Test
    fun theNearestCopyWins() {
        val w = words("soft light a b c d e f soft light")
        val moved = Highlights.reanchor(listOf(h("soft light", 9, 10)), w)!!.single()
        assertEquals(8, moved.w0)
    }

    @Test
    fun aHighlightThatCannotBeFoundStays() {
        val w = words("nothing like it here")
        assertNull(Highlights.reanchor(listOf(h("Hard light", 7, 8)), w))
    }
}
