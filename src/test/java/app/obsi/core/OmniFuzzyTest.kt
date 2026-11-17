package app.obsi.core

import app.obsi.core.data.Omni
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OmniFuzzyTest {
    @Test fun lettersSpreadOverALongTitleDoNotMatch() {
        assertNull(Omni.fuzzy("#147 How to Master Long-Distance Relationships - with Aida Manduley", "lonly"))
        assertNull(Omni.fuzzy("#450 The Art of Lasting Passion - Your Mashup with Dr. Aly", "lonly"))
        assertNull(Omni.fuzzy("The Effects of Microplastics on Your Health & How to Reduce Them", "iheart"))
        assertNull(Omni.fuzzy("Charting the Architecture of the Universe & Human Life | Dr. Brian Keating", "iheart"))
        assertNull(Omni.fuzzy("How Smell, Taste & Pheromone-Like Chemicals Control You", "lonly"))
    }

    @Test fun typosAndWordStartsStillMatch() {
        assertNotNull(Omni.fuzzy("Feeling Lonely at Night", "lonly"))
        assertNotNull(Omni.fuzzy("Long-Distance Relationships", "ldr"))
        assertNotNull(Omni.fuzzy("My Friends", "frnds"))
        assertNotNull(Omni.fuzzy("Long-Distance Relationships", "distance"))
    }
}
