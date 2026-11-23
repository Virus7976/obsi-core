package app.obsi.core.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Microsoft Edge's free online voices: the same "Read aloud" voices the Edge browser and Readest use.
 * No key and no account. This is not an official API, so Microsoft can change or block it at any time;
 * audio already made is kept, so it still plays.
 *
 * The way in is ported from the open-source edge-tts project (github.com/rany2/edge-tts, 7.3.x, by rany2,
 * LGPL-3.0). This file stays under the LGPL-3.0, as that licence asks. edge-tts
 * keeps it working when Microsoft changes the lock. When Edge voices stop with "403", compare
 * [Token] and the version numbers below with edge-tts's drm.py and constants.py.
 */
object EdgeVoices {
    private const val TRUSTED = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val BASE = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    private const val CHROMIUM = "143.0.3650.75"
    private val MAJOR = CHROMIUM.substringBefore('.')
    private val GEC_VERSION = "2-$CHROMIUM"
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$MAJOR.0.0.0 Safari/537.36 Edg/$MAJOR.0.0.0"

    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** How far this phone's clock is off Microsoft's, in seconds. Learnt from a refused request. */
    @Volatile private var skew = 0.0

    private fun muid(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }

    /** Every Edge voice. Network call: run it off the main thread. */
    fun voices(): List<SpeakerVoice> {
        var last: IOException? = null
        repeat(2) {
            val url = "https://$BASE/voices/list?trustedclienttoken=$TRUSTED&Sec-MS-GEC=${Token.now(skew)}&Sec-MS-GEC-Version=$GEC_VERSION"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-CH-UA", "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"$MAJOR\", \"Chromium\";v=\"$MAJOR\"")
                .header("Sec-CH-UA-Mobile", "?0")
                .header("Sec-Fetch-Site", "none").header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Dest", "empty")
                .header("Cookie", "muid=${muid()};")
                .build()
            client.newCall(req).execute().use { r ->
                if (r.code == 403 && learnSkew(r)) { last = IOException("Edge refused (403)."); return@repeat }
                if (!r.isSuccessful) throw IOException("Edge voices answered ${r.code}.")
                return parse(r.body!!.string())
            }
        }
        throw last ?: IOException("Edge voices are not answering.")
    }

    fun parse(body: String): List<SpeakerVoice> {
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val short = s("ShortName").ifBlank { return@mapNotNull null }
            val locale = s("Locale")
            // "en-US-EmmaMultilingualNeural" is shown as "Emma".
            val bare = short.removePrefix("$locale-").removeSuffix("Neural")
            val multi = bare.endsWith("Multilingual")
            val personality = runCatching {
                o["VoiceTag"]?.jsonObject?.get("VoicePersonalities")?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.take(2).orEmpty()
            }.getOrDefault(emptyList())
            SpeakerVoice(
                id = "edge:$short",
                name = bare.removeSuffix("Multilingual").replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").ifBlank { short },
                locale = locale,
                gender = s("Gender"),
                kind = VoiceKind.EDGE,
                note = (listOfNotNull(if (multi) "Multilingual" else null) + personality).joinToString(", "),
            )
        }
    }

    /**
     * Speaks [text] with [shortName] into [out] as MP3. Returns when each word was said, in seconds from
     * the start, with the word. Blocks: call it off the main thread.
     */
    fun synth(shortName: String, text: String, out: File): List<Pair<Double, String>> {
        var last: IOException? = null
        repeat(2) {
            try {
                return once(shortName, text, out)
            } catch (e: Refused) {
                last = IOException("Edge refused the voice request (${e.code}).")
                if (!e.learnt) throw last as IOException
            }
        }
        throw last ?: IOException("Edge did not answer.")
    }

    private class Refused(val code: Int, val learnt: Boolean) : IOException()

    private fun once(shortName: String, text: String, out: File): List<Pair<Double, String>> {
        val id = UUID.randomUUID().toString().replace("-", "")
        val url = "wss://$BASE/edge/v1?TrustedClientToken=$TRUSTED&ConnectionId=$id&Sec-MS-GEC=${Token.now(skew)}&Sec-MS-GEC-Version=$GEC_VERSION"
        val req = Request.Builder().url(url)
            .header("Pragma", "no-cache").header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${muid()};")
            .build()

        val audio = ByteArrayOutputStream()
        val words = ArrayList<Pair<Double, String>>()
        val done = CountDownLatch(1)
        var error: IOException? = null

        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val now = dateString()
                webSocket.send(
                    "X-Timestamp:$now\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"}," +
                        "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n",
                )
                webSocket.send(
                    "X-RequestId:$id\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${now}Z\r\nPath:ssml\r\n\r\n" + ssml(shortName, text),
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val split = text.indexOf("\r\n\r\n")
                val head = if (split >= 0) text.substring(0, split) else text
                val body = if (split >= 0) text.substring(split + 4) else ""
                when {
                    head.contains("Path:turn.end") -> { webSocket.close(1000, null); done.countDown() }
                    head.contains("Path:audio.metadata") -> runCatching {
                        val meta = json.parseToJsonElement(body).jsonObject["Metadata"]?.jsonArray.orEmpty()
                        for (m in meta) {
                            val o = m.jsonObject
                            if ((o["Type"] as? JsonPrimitive)?.contentOrNull != "WordBoundary") continue
                            val d = o["Data"]?.jsonObject ?: continue
                            val at = (d["Offset"] as? JsonPrimitive)?.doubleOrNull ?: continue
                            val w = (d["text"]?.jsonObject?.get("Text") as? JsonPrimitive)?.contentOrNull ?: continue
                            synchronized(words) { words += at / 10_000_000.0 to w }
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val headLen = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
                if (2 + headLen > b.size) return
                val head = String(b, 2, headLen, Charsets.UTF_8)
                if (!head.contains("Path:audio")) return
                synchronized(audio) { audio.write(b, 2 + headLen, b.size - 2 - headLen) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                error = when {
                    response != null && (response.code == 403 || response.code == 401) -> Refused(response.code, learnSkew(response))
                    t is IOException -> t
                    else -> IOException(t.message ?: "Edge did not answer.")
                }
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
        })

        // A long paragraph takes a few seconds. A minute means something is wrong.
        if (!done.await(60, TimeUnit.SECONDS)) { ws.cancel(); throw IOException("Edge took too long.") }
        error?.let { throw it }
        val bytes = synchronized(audio) { audio.toByteArray() }
        if (bytes.isEmpty()) throw IOException("Edge sent no sound.")
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
        return synchronized(words) { words.sortedBy { it.first } }
    }

    private fun ssml(shortName: String, text: String): String {
        val locale = shortName.split('-').take(2).joinToString("-").ifBlank { "en-US" }
        val name = shortName.removePrefix("$locale-")
        val safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='Microsoft Server Speech Text to Speech Voice ($locale, $name)'>" +
            "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>$safe</prosody></voice></speak>"
    }

    private fun dateString(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    /** A refusal can mean the phone's clock is off. Learns the gap from the server's Date. True when it learnt something. */
    private fun learnSkew(r: Response): Boolean {
        val date = r.header("Date") ?: return false
        val server = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(date)!!.time / 1000.0
        }.getOrNull() ?: return false
        val gap = server - (System.currentTimeMillis() / 1000.0 + skew)
        if (kotlin.math.abs(gap) < 30) return false
        skew += gap
        return true
    }

    /**
     * The Sec-MS-GEC token, version 2: a reworked SHA-256 and a "CS64" check of the time and the client
     * token. A line-for-line port of edge-tts's drm.py (generate_sec_ms_gec_v2). Do not tidy the maths:
     * one changed constant gives a wrong token and a 403.
     */
    object Token {
        private const val WIN_EPOCH = 11644473600.0

        private val INITIAL = intArrayOf(0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(), 0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19)
        private val STATE = intArrayOf(0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(), 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4.toInt(), 0x03EFFBD2)
        private val SCHEDULE = intArrayOf(
            0xEB24BF9E.toInt(), 0x1558691F, 0xA0C09EE2.toInt(), 0x0CFA32BA, 0xA622EE8C.toInt(), 0xD1DD9491.toInt(), 0xC0DD25A0.toInt(),
            0xF9E70AB6.toInt(), 0xF0C31573.toInt(), 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060.toInt(), 0x72766564,
        )
        private val RC = longArrayOf(
            0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
            0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
            0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
            0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
            0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
            0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
            0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
            0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
        ).map { it.toInt() }.toIntArray()
        private const val ADJUST = 0xC9DC06A7.toInt()
        private val BLOCK_RC = RC.copyOf().also { it[14] = ADJUST }
        private const val SIGMA0_ADJUST = 0x059C0B80
        private const val SIGMA1_ADJUST = 0x000B92C0

        private val CS64 = arrayOf(
            0xE82E91B1 to 0xB32700D0, 0x44DE24C6 to 0x88DFF51D, 0x3BEB65BC to 0x29514038, 0xC89C5933 to 0xB328CBDF,
            0x8D1EC211 to 0xD405B4D5, 0x314EF5D5 to 0x26E55F3C, 0xE92491B1 to 0x607801C6, 0xF0A5B9D5 to 0x82B2C1FB,
            0x06064CF3 to 0xFA51A3DA, 0xDA79FA60 to 0x207CC464, 0xBED20560 to 0x3C3C74F3, 0x89F9C9F8 to 0xD2D14B61,
            0x35FA38C6 to 0xBB04A2C1, 0xCB49E3C6 to 0xC5626167,
        ).map { (a, b) -> a.toInt() to b.toInt() }
        private const val CS64_FACTOR = 0x82893933.toInt()
        private const val CS64_ADJUST0 = 0xF2F90B1A.toInt()
        private const val CS64_ADJUST1 = 0x0B1A0000

        /** The token for now, with the clock gap [skew] in seconds. */
        fun now(skew: Double = 0.0): String = at(System.currentTimeMillis() / 1000.0 + skew)

        /** The token for a Unix time in seconds. */
        fun at(unixSeconds: Double): String {
            var ticks = unixSeconds + WIN_EPOCH
            ticks -= ticks % 300
            ticks *= 1e9 / 100
            // Python prints the double's exact value; BigDecimal does the same.
            val s = BigDecimal(ticks).setScale(0, RoundingMode.HALF_EVEN).toPlainString() + TRUSTED
            val data = s.toByteArray(Charsets.US_ASCII)
            val digest = sha256(data)
            val (c0, c1) = cs64(data, digest)
            val cs = ByteArray(8).also { le32(it, 0, c0); le32(it, 4, c1) }
            return hex(cs) + "-" + hex(digest)
        }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
        private fun le32(b: ByteArray, at: Int, v: Int) { for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte() }
        private fun rol16(v: Int) = Integer.rotateLeft(v, 16)
        private fun s0(v: Int) = Integer.rotateRight(v, 7) xor Integer.rotateRight(v, 18) xor (v ushr 3)
        private fun s1(v: Int) = Integer.rotateRight(v, 17) xor Integer.rotateRight(v, 19) xor (v ushr 10)

        private fun rounds(state: IntArray, w: IntArray, first: Int = 0): IntArray {
            var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
            var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
            val k = if (first == 14) BLOCK_RC else RC
            for (i in first until 64) {
                val t1 = h + (Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)) + (g xor (e and (f xor g))) + k[i] + w[i]
                val t2 = (Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)) + ((a and b) or (c and (a or b)))
                h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            return intArrayOf(a, b, c, d, e, f, g, h)
        }

        private fun expand(w: IntArray, first: Int = 16) {
            for (i in first until 64) w[i] = s1(w[i - 2]) + w[i - 7] + s0(w[i - 15]) + w[i - 16]
        }

        private fun be(data: ByteArray, from: Int, n: Int): Int {
            var v = 0
            for (i in from until from + n) v = (v shl 8) or (data[i].toInt() and 0xff)
            return v
        }

        private fun init(seven: ByteArray): IntArray {
            val w = IntArray(64)
            w[14] = be(seven, 0, 3)
            w[15] = be(seven, 3, 4)
            for (i in 16 until 30) {
                var v = SCHEDULE[i - 16]
                if (i - 2 >= 14) v += if (i == 16) s1(w[i - 2]) xor SIGMA1_ADJUST else s1(w[i - 2])
                if (i - 7 >= 14) v += w[i - 7]
                if (i - 15 >= 14) v += if (i == 29) s0(w[i - 15]) xor SIGMA0_ADJUST else s0(w[i - 15])
                w[i] = v
            }
            w[30] = s1(w[28]) + w[23] + s0(w[15]) + w[14] + ADJUST - RC[14]
            expand(w, 31)
            val work = rounds(STATE, w, 14)
            return IntArray(8) { INITIAL[it] + work[it] }
        }

        fun sha256(data: ByteArray): ByteArray {
            require(data.size >= 7) { "Token salt must contain at least seven bytes" }
            val length = 57L + data.size
            val zeros = Math.floorMod(55 - length, 64L).toInt()
            val tail = data.copyOfRange(7, data.size)
            val padded = ByteArray(tail.size + 1 + zeros + 8)
            tail.copyInto(padded)
            padded[tail.size] = 0x80.toByte()
            val bits = length * 8
            for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
            var state = init(data.copyOfRange(0, 7))
            var off = 0
            while (off < padded.size) {
                val w = IntArray(64)
                for (i in 0 until 16) w[i] = be(padded, off + i * 4, 4)
                expand(w)
                val work = rounds(state, w)
                state = IntArray(8) { state[it] + work[it] }
                off += 64
            }
            return ByteArray(32).also { out -> for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (state[i] ushr (24 - 8 * j)).toByte() }
        }

        private fun cs64(data: ByteArray, digest: ByteArray): Pair<Int, Int> {
            fun le(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
            val ka = le(digest, 0) or 1
            val kb = le(digest, 4) or 1
            val factors = intArrayOf(ka * CS64_FACTOR, kb * CS64_FACTOR)
            val terms = ArrayList<Int>()
            val offsets = ArrayList<Int>()
            CS64.forEachIndexed { i, (c, o) -> terms += c * factors[i % 2]; offsets += o }
            // The data with a zero byte in front, as little-endian words, padded to an even count.
            val src = ByteArray(1) + data
            val padded = src.copyOf(src.size + Math.floorMod(-src.size, 4))
            val words = ArrayList<Int>()
            for (i in 0 until padded.size / 4) words += le(padded, i * 4)
            if (words.size % 2 == 1) words += 0
            words.forEachIndexed { i, word ->
                var term = word * (if (i % 2 == 0) ka else kb)
                var offset = rol16(word) * (if (i % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
                if (i == 0) { term += CS64_ADJUST0 * factors[0]; offset += CS64_ADJUST1 }
                terms += term; offsets += offset
            }
            var swapAcc = 0; var swapLast = 0; var state = 0; var acc = 0
            var i = 0
            while (i < terms.size) {
                val x = terms[i]; val y = terms[i + 1]
                var t = rol16(offsets[i] + x)
                var q = t * 0x76F50F34
                swapAcc += rol16(q) * 0xC3EFEA97.toInt() + q
                t = rol16(offsets[i + 1] + y)
                q = t * 0x1881CE3C
                swapLast = rol16(q) * 0x35BD1EC9 + q
                swapAcc += swapLast

                state = state * ka + x
                state = rol16(state) * 0xCF98B111.toInt()
                state = rol16(state) * 0x87085B9F.toInt()
                state = rol16(state) * 0x12CEB96D
                state = rol16(state) * 0x257E1D83
                acc += state
                t = state * kb + y
                t = rol16(t) * 0xA27416F5.toInt()
                t = rol16(t) * 0xD38396FF.toInt()
                state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69.toInt()
                acc += state
                i += 2
            }
            return (swapLast xor state) to (swapAcc xor acc)
        }
    }
}
