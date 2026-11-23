package app.obsi.core.speech

import app.obsi.core.data.ArticleDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenSkipTest {
    private val all = SkipPart.entries.filter { it.default }.map { it.key }.toSet()

    private val wiki = """
        <p>Speech is made by machines.<sup><a href="#fn:1">1</a></sup> It is old.[2]</p>
        <figure><img src="a.png"><figcaption>A talking machine</figcaption></figure>
        <h2>History</h2>
        <p>See https://example.com/page for more (born 1950).</p>
        <table><tr><td>Year</td><td>Name</td></tr></table>
        <pre>val x = 1</pre>
        <h2>References</h2>
        <p>Allen, J. From Text to Speech.</p>
        <h3>Books</h3>
        <p>A book.</p>
        <h2>Legacy</h2>
        <p>Still used today.</p>
        <div><ol><li><p>Klatt, D. Review.<a href="#fnref:1">↩</a></p></li></ol></div>
    """.trimIndent()

    private fun doc() = ListenSkip.ofArticle("w", "Speech", "Wikipedia", ArticleDoc.build(wiki))
    private fun read(skip: Set<String> = all): List<String> = ListenSkip.apply(doc(), skip)
    private fun spoken(skip: Set<String> = all) = read(skip).joinToString(" ").replace(Regex("\\s+"), " ").trim()

    @Test
    fun everyParagraphKeepsItsLength() {
        val d = doc()
        assertEquals(d.paras.map { it.length }, read().map { it.length })
    }

    @Test
    fun theSourcesSectionIsSkippedUpToTheNextSectionOfItsLevel() {
        val s = spoken()
        assertFalse(s.contains("Allen"))
        assertFalse(s.contains("A book"))
        assertTrue(s.contains("Still used today"))
    }

    @Test
    fun aFootnoteListWithNoHeadingIsSkipped() {
        assertFalse(spoken().contains("Klatt"))
    }

    @Test
    fun footnoteNumbersGoButTheSentenceStays() {
        val s = spoken()
        assertTrue(s.contains("Speech is made by machines. It is old."))
        assertFalse(s.contains("[2]"))
    }

    @Test
    fun captionsTablesCodeAndWebAddressesAreSkipped() {
        val s = spoken()
        assertFalse(s.contains("talking machine"))
        assertFalse(s.contains("Year"))
        assertFalse(s.contains("val x"))
        assertFalse(s.contains("example.com"))
        assertTrue(s.contains("See for more"))
    }

    @Test
    fun bracketsAreReadUnlessAskedToSkip() {
        assertTrue(spoken().contains("(born 1950)"))
        assertFalse(spoken(all + SkipPart.BRACKETS.key).contains("born"))
    }

    @Test
    fun nothingIsSkippedWhenEverySwitchIsOff() {
        val s = spoken(emptySet())
        listOf("Allen", "Klatt", "talking machine", "Year", "val x", "example.com", "machines.1").forEach {
            assertTrue("missing $it", s.contains(it))
        }
    }

    @Test
    fun sourceHeadingsAreKnown() {
        listOf("References", "Notes", "See also", "External links", "Further reading", "Bibliography:", "Works cited").forEach {
            assertTrue(it, ListenSkip.isSourceHeading(it))
        }
        listOf("History", "Notes on the war", "Sources of energy").forEach { assertFalse(it, ListenSkip.isSourceHeading(it)) }
    }
}
