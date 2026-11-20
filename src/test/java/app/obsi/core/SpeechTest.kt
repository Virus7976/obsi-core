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
        // A steady level stays the same level once the filter has settled.
        assertTrue(r.push(ShortArray(96) { 1000 }, 2, 48_000).first() in 998..1000)
    }

    /** How loud a tone of [hz] at 48 kHz comes out at 16 kHz, 0..1. */
    private fun gain(hz: Double): Double {
        val r = Pcm.Resampler()
        val inp = ShortArray(48_000) { (10_000 * kotlin.math.sin(2 * Math.PI * hz * it / 48_000)).toInt().toShort() }
        val out = r.push(inp, 1, 48_000).drop(1600)
        return kotlin.math.sqrt(out.sumOf { it.toDouble() * it } / out.size) / (10_000 / kotlin.math.sqrt(2.0))
    }

    @Test fun voiceStaysAndHighSoundIsTakenOut() {
        // Speech sits well under 4 kHz and must come through.
        assertTrue("1 kHz ${gain(1000.0)}", gain(1000.0) > 0.95)
        // 12 kHz would fold down to 4 kHz, right on the voice. It must be nearly gone.
        assertTrue("12 kHz ${gain(12_000.0)}", gain(12_000.0) < 0.12)
    }

    @Test fun cutsAtTheQuietMoment() {
        // Ten seconds of loud sound with a quiet spot at 8 s.
        val n = Pcm.RATE * 10
        val bb = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putShort(if (i in Pcm.RATE * 8 until Pcm.RATE * 8 + 3200) 0 else (if (i % 2 == 0) 8000 else -8000).toShort())
        val cut = Pcm.quietCut(bb.array(), Pcm.RATE * 5) / 2
        assertTrue("cut at $cut", cut in Pcm.RATE * 8 until Pcm.RATE * 8 + 3200)
        assertEquals(0, Pcm.quietCut(bb.array(), Pcm.RATE * 5) % 2)
    }

    @Test fun keepsCapitalsAndStopsFromTheSegments() {
        val w = Cloud.parse(
            """{"segments":[{"start":0.0,"end":2.0,"text":" Hi there, Jake. It's 5 o'clock — late."}],
               "words":[{"word":"Hi","start":0.0,"end":0.2},{"word":"there","start":0.3,"end":0.5},{"word":"Jake","start":0.6,"end":0.9},
                        {"word":"It's","start":1.0,"end":1.1},{"word":"5","start":1.2,"end":1.3},{"word":"o'clock","start":1.3,"end":1.6},{"word":"late","start":1.7,"end":2.0}]}""",
        )
        assertEquals(listOf("Hi", "there,", "Jake.", "It's", "5", "o'clock —", "late."), w.map { it.text })
        assertEquals(listOf(0.0, 0.3, 0.6, 1.0, 1.2, 1.3, 1.7), w.map { it.start })
    }

    @Test fun aWordMissingFromTheTimesStillShows() {
        val w = Cloud.parse(
            """{"segments":[{"start":0.0,"end":1.0,"text":"One extra two."}],"words":[{"word":"one","start":0.0,"end":0.3},{"word":"two","start":0.6,"end":0.9}]}""",
        )
        assertEquals(listOf("One", "extra", "two."), w.map { it.text })
        assertEquals(listOf(0.0, 0.3, 0.6), w.map { it.start })
    }
}
