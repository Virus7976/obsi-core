package app.obsi.core

import app.obsi.core.data.Transcripts
import app.obsi.core.speech.Cloud
import app.obsi.core.speech.Pcm
import app.obsi.core.speech.Speech
import app.obsi.core.speech.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptsTest {
    @Test fun readsSrt() {
        val srt = "1\r\n00:00:01,000 --> 00:00:03,500\r\nHello <i>there</i>.\r\n\r\n2\r\n00:00:04,000 --> 00:00:06,000\r\nSecond line\r\nwraps here.\r\n"
        val t = Transcripts.parseSubtitles(srt)
        assertEquals(2, t.cues.size)
        assertEquals("Hello there.", t.cues[0].text)
        assertEquals(1.0, t.cues[0].start, 0.001)
        assertEquals(2.5, t.cues[0].dur, 0.001)
        assertEquals("Second line wraps here.", t.cues[1].text)
    }

    @Test fun readsVttAndDropsRollingRepeats() {
        val vtt = "WEBVTT\n\n00:00.000 --> 00:02.000 align:start\nwelcome to\n\n00:02.000 --> 00:04.000\nwelcome to the course\n\n1:00:00.000 --> 1:00:01.000\nlate\n"
        val t = Transcripts.parseSubtitles(vtt)
        assertEquals(listOf("welcome to", "the course", "late"), t.cues.map { it.text })
        assertEquals(3600.0, t.cues[2].start, 0.001)
    }

    @Test fun vttRoundTrip() {
        val t = Transcripts.parseSubtitles("WEBVTT\n\n00:00:01.250 --> 00:00:02.000\nOne\n")
        assertEquals(t.cues, Transcripts.parseSubtitles(Transcripts.toVtt(t)).cues)
    }
}

class SpeechTest {
    @Test fun cuesKeepEachWordsTime() {
        val words = listOf(
            Word("Hello", 0.0, 0.4), Word("world.", 0.5, 0.9),
            // A long pause starts a new cue.
            Word("Next", 3.0, 3.3), Word("part", 3.4, 3.8),
        )
        val cues = Speech.cues(words)
        assertEquals(listOf("Hello world.", "Next part"), cues.map { it.text })
        assertEquals(listOf(0.0, 0.5), cues[0].offs)
        assertEquals(3.0, cues[1].start, 0.0)
    }

    @Test fun cloudWordsAndSegments() {
        val w = Cloud.parse("""{"text":"hi there","words":[{"word":" hi","start":0.1,"end":0.3},{"word":"there","start":0.4,"end":0.8}]}""")
        assertEquals(listOf("hi", "there"), w.map { it.text })
        val s = Cloud.parse("""{"segments":[{"start":10.0,"end":12.0,"text":" one two"}]}""")
        assertEquals(listOf(10.0, 11.0), s.map { it.start })
    }

    @Test fun resamplesToSixteenKilohertzMono() {
        val r = Pcm.Resampler()
        // One second of 48 kHz stereo, in four blocks, comes out as about one second at 16 kHz.
        var n = 0
        repeat(4) { n += r.push(ShortArray(48_000 / 4 * 2) { 1000 }, 2, 48_000).size }
        assertTrue(n in 15_990..16_010)
        assertEquals(1000.toShort(), r.push(ShortArray(96) { 1000 }, 2, 48_000).first())
    }
}
