package app.obsi.core.speech

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packs a phone voice's WAV into a small AAC file (.m4a) with the phone's own encoder, for audio kept
 * offline. A minute of speech goes from about 2.9 MB to about 0.4 MB.
 */
object AudioPack {
    private const val BITRATE = 56_000

    /** Writes [wav] as AAC into [out]. Throws when the phone cannot encode it; the caller then keeps the WAV. */
    fun wavToM4a(wav: File, out: File) {
        val h = Wav.read(wav) ?: throw IOException("Not a WAV file.")
        if (h.bits != 16) throw IOException("Only 16-bit sound is packed.")
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, h.rate, h.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val tmp = File(out.path + ".part")
        out.parentFile?.mkdirs()
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            RandomAccessFile(wav, "r").use { r ->
                r.seek(h.dataStart)
                val frameBytes = 2 * h.channels
                val total = r.length() - h.dataStart
                var read = 0L
                var inputDone = false
                val info = MediaCodec.BufferInfo()
                val chunk = ByteArray(8192)
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf: ByteBuffer = codec.getInputBuffer(i)!!
                            buf.clear()
                            val want = minOf(buf.remaining(), chunk.size).let { it - it % frameBytes }
                            val n = r.read(chunk, 0, want)
                            val pts = read * 1_000_000L / (h.rate.toLong() * frameBytes)
                            if (n <= 0 || read >= total) {
                                codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.order(ByteOrder.LITTLE_ENDIAN).put(chunk, 0, n)
                                codec.queueInputBuffer(i, 0, n, pts, 0)
                                read += n
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxing = true
                        }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxing) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buf, info)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (muxing) muxer.stop() }
            runCatching { muxer.release() }
        }
        if (!muxing || tmp.length() < 100) { tmp.delete(); throw IOException("The phone could not pack the sound.") }
        if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
    }
}
