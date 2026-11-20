package app.obsi.core.speech

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Decodes the sound of a video or audio file into 16 kHz mono 16-bit samples, the form speech
 * recognition wants. It runs on the phone with the phone's own decoders.
 */
object Pcm {
    const val RATE = 16_000

    /**
     * Calls [onSamples] with each block of samples, in order. [onProgress] gets 0..1 when the length is known.
     * Throws when the file has no sound the phone can decode.
     */
    suspend fun decode(
        context: Context,
        uri: Uri,
        headers: Map<String, String>,
        onProgress: (Float) -> Unit,
        onSamples: (ShortArray) -> Unit,
    ) {
        val ex = MediaExtractor()
        try {
            when (uri.scheme) {
                "file" -> ex.setDataSource(uri.path!!)
                else -> customSource?.invoke(uri)?.let { ex.setDataSource(it) } ?: ex.setDataSource(context, uri, headers)
            }
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("This lesson has no sound to transcribe.")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val resampler = Resampler()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                if (durUs > 0) onProgress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f))
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(o)!!
                            out.position(info.offset); out.limit(info.offset + info.size)
                            val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val raw = ShortArray(sb.remaining()); sb.get(raw)
                            val mono = resampler.push(raw, channels, srcRate)
                            if (mono.isNotEmpty()) onSamples(mono)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            ex.release()
        }
    }

    /**
     * For addresses the phone cannot open by itself, like smb:// shares. The app returns a reader for the
     * address, or null to let the phone try.
     */
    var customSource: ((Uri) -> android.media.MediaDataSource?)? = null

    /**
     * Mixes to mono and changes the rate to 16 kHz, by straight lines between samples. Keeps its place between
     * blocks. Sound above 7 kHz is taken out first: at 16 kHz it cannot be kept, and left in it folds back
     * down as a hiss over the voice, which makes speech recognition much less exact.
     */
    class Resampler {
        private var pos = 0.0
        private var last = 0.0
        private val low1 = LowPass()
        private val low2 = LowPass()

        fun push(raw: ShortArray, channels: Int, rate: Int): ShortArray {
            val ch = channels.coerceAtLeast(1)
            val frames = raw.size / ch
            if (frames == 0) return ShortArray(0)
            val mono = DoubleArray(frames) { f -> var s = 0.0; for (c in 0 until ch) s += raw[f * ch + c]; s / ch }
            // Two filters in a row cut the high sound off more steeply than one.
            if (rate > RATE) { low1.run(mono, rate); low2.run(mono, rate) }
            val step = rate.toDouble() / RATE
            val out = ArrayList<Short>((frames / step).toInt() + 2)
            // pos is where the next output sample sits, counted from the end of the block before (-1 is its last sample).
            while (pos < frames - 1) {
                val i = kotlin.math.floor(pos).toInt()
                val frac = pos - i
                val a = if (i < 0) last else mono[i]
                val b = mono[i + 1]
                out += (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
                pos += step
            }
            pos -= frames
            last = mono[frames - 1]
            return out.toShortArray()
        }
    }

    /** A low-pass filter at 7 kHz (a Butterworth biquad). Keeps its state between blocks. */
    class LowPass {
        private var rate = 0
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        fun run(s: DoubleArray, rate: Int) {
            if (rate != this.rate) {
                this.rate = rate
                val w = 2 * Math.PI * 7000.0 / rate
                val alpha = kotlin.math.sin(w) / (2 * kotlin.math.sqrt(0.5))
                val cos = kotlin.math.cos(w)
                val a0 = 1 + alpha
                b0 = (1 - cos) / 2 / a0; b1 = (1 - cos) / a0; b2 = b0
                a1 = -2 * cos / a0; a2 = (1 - alpha) / a0
            }
            for (i in s.indices) {
                val x = s[i]
                val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x; y2 = y1; y1 = y
                s[i] = y
            }
        }
    }

    /**
     * Where to cut 16-bit mono sound [pcm] (bytes) so no word is cut in two: the quietest tenth of a second
     * in the last [within] samples. Returns a byte index, always even.
     */
    fun quietCut(pcm: ByteArray, within: Int): Int {
        val n = pcm.size / 2
        val win = RATE / 10
        if (n <= win * 2) return n * 2
        val from = (n - within).coerceAtLeast(n / 2)
        val bb = java.nio.ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        var best = n
        var bestSum = Long.MAX_VALUE
        var i = from
        while (i + win <= n) {
            var sum = 0L
            var k = i
            while (k < i + win) { sum += kotlin.math.abs(bb.getShort(k * 2).toInt()); k += 4 }
            if (sum < bestSum) { bestSum = sum; best = i + win / 2 }
            i += win / 2
        }
        return best * 2
    }

    /** A WAV file header for 16 kHz mono 16-bit samples, [bytes] long. */
    fun wavHeader(bytes: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + bytes); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(bytes)
        return b.array()
    }
}
