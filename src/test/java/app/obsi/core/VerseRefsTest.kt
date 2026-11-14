package app.obsi.core

import app.obsi.core.link.VerseRefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerseRefsTest {
    @Test fun findsVerses() {
        assertEquals("John 3:16", VerseRefs.find("As it says in John 3:16, God so loved"))
        assertEquals("1 Cor 13:4-7", VerseRefs.find("read 1 Cor 13:4-7 tonight"))
        assertEquals("Romans 8:28", VerseRefs.find("Romans 8:28."))
        assertEquals("Psalm 23", VerseRefs.find("We sang Psalm 23 together"))
        assertEquals("2 Timothy 3:16", VerseRefs.find("2 Timothy 3:16"))
    }

    @Test fun skipsNamesAndNumbers() {
        assertNull(VerseRefs.find("Mark 2 people down for Tuesday"))
        assertNull(VerseRefs.find("Job 3 is done"))
        assertNull(VerseRefs.find("The meeting is at 3:16 pm"))
        assertNull(VerseRefs.find("Johnson 3:16"))
    }
}
