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

    /** Mixes to mono and changes the rate to 16 kHz, by straight lines between samples. Keeps its place between blocks. */
    class Resampler {
        private var pos = 0.0
        private var last = 0.0

        fun push(raw: ShortArray, channels: Int, rate: Int): ShortArray {
            val ch = channels.coerceAtLeast(1)
            val frames = raw.size / ch
            if (frames == 0) return ShortArray(0)
            val mono = DoubleArray(frames) { f -> var s = 0.0; for (c in 0 until ch) s += raw[f * ch + c]; s / ch }
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

    /** A WAV file header for 16 kHz mono 16-bit samples, [bytes] long. */
    fun wavHeader(bytes: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + bytes); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(RATE); b.putInt(RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(bytes)
        return b.array()
    }
}
