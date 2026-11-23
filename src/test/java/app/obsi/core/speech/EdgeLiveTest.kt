package app.obsi.core.speech

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Talks to Microsoft for real. Runs only with EDGE_LIVE=1. */
class EdgeLiveTest {
    @Test fun speaks() {
        assumeTrue(System.getenv("EDGE_LIVE") == "1")
        val voices = EdgeVoices.voices()
        println("voices: ${voices.size}, e.g. ${voices.filter { it.language == "en" }.take(5).map { it.id }}")
        val out = File.createTempFile("edge", ".mp3")
        val text = "Hello there. This is a test of the Edge voice, reading one sentence."
        val words = EdgeVoices.synth("en-US-EmmaMultilingualNeural", text, out)
        println("bytes: ${out.length()}, words: ${words.take(6)}")
        println("placed: ${SpeechTimings.placeWords(text, words).take(4)}")
        check(out.length() > 5000) { "too little sound" }
        check(words.isNotEmpty()) { "no word times" }
    }
}
