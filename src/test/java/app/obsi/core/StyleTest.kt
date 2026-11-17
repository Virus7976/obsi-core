package app.obsi.core

import app.obsi.core.data.AppSettings
import app.obsi.core.share.ObsiStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleTest {
    @Test fun bibleSharesOnlyLightOrDark() {
        val mine = AppSettings().copy(themePreset = "amber", appearance = "dark")
        val bible = ObsiStyle(at = 5L, appearance = "oled", full = false)
        val after = bible.applyTo(mine)
        assertEquals("amber", after.themePreset)
        assertEquals("oled", after.appearance)
        assertEquals(5L, after.styleAt)
    }

    @Test fun sameLightOrDarkIsTheSameForBible() {
        val bible = ObsiStyle(appearance = "oled", full = false)
        assertTrue(bible.sameLook(ObsiStyle(themePreset = "ruby", appearance = "oled")))
    }

    @Test fun wholeLookCarriesTheColour() {
        val after = ObsiStyle(at = 9L, themePreset = "amber", appearance = "oled").applyTo(AppSettings())
        assertEquals("amber", after.themePreset)
        assertEquals("oled", after.appearance)
    }
}
