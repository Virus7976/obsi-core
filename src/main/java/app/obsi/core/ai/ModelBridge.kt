package app.obsi.core.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Lends the phone's own AI model to other apps on this phone, like Obsidian, with the same
 * OpenAI-style requests a model app on a computer takes.
 *
 * Safe by design:
 * - It listens on 127.0.0.1 only, so nothing off the phone can reach it.
 * - Every request that uses the model needs the key from a link the person allowed.
 * - Web pages are refused: a request with a web page's Origin, or for another host name, gets 403.
 * - Requests are small (64 KB), answers short (512 tokens), and one runs at a time.
 */
object ModelBridge {
    /** The same in every Obsi app, so Obsidian knows where to look. */
    const val PORT = 47391
    const val MAX_BODY = 64 * 1024
    const val MAX_HEAD = 8 * 1024
    const val MAX_TOKENS = 512
    /** The only Origins allowed: Obsidian on a phone, and none at all. */
    internal val ORIGINS = setOf("app://obsidian.md", "capacitor://localhost", "http://localhost")
}

/** What the bridge needs from the app. Kept small so the bridge can be tested without a phone. */
interface BridgeEnv {
    /** The app's name, like "ObsiTube". */
    val app: String
    /** A model is on the phone and can answer. */
    fun ready(): Boolean
    /** The model's name, for a short line in Obsidian. */
    fun model(): String
    /** Whether this key comes from a link the person allowed. */
    fun allowed(token: String): Boolean
    suspend fun ask(system: String, user: String, maxTokens: Int): String
    /** Called after each answer, so the bridge stays on while it is used. */
    fun used() {}
}

data class HttpReq(val method: String, val path: String, val headers: Map<String, String>, val body: String)

data class HttpRes(val status: Int, val body: String) {
    companion object {
        fun json(status: Int, o: JsonObject) = HttpRes(status, o.toString())
        fun error(status: Int, message: String) = json(status, buildJsonObject { put("error", buildJsonObject { put("message", message) }) })
    }
}

object BridgeHttp {
    private val askLock = Mutex()

    /** Reads one request. Null when it is broken or too big. */
    fun read(input: InputStream): HttpReq? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > ModelBridge.MAX_HEAD) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val first = lines.firstOrNull()?.split(" ") ?: return null
        if (first.size < 3) return null
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > ModelBridge.MAX_BODY) return HttpReq(first[0], first[1], headers + ("x-too-big" to "1"), "")
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(body, got, length - got)
            if (n < 0) return null
            got += n
        }
        return HttpReq(first[0].uppercase(), first[1].substringBefore('?'), headers, String(body, Charsets.UTF_8))
    }

    suspend fun handle(req: HttpReq, env: BridgeEnv, port: Int = ModelBridge.PORT): HttpRes {
        // Only this phone, by this phone's own name: blocks pages that point a web name at 127.0.0.1.
        val host = req.headers["host"].orEmpty().lowercase()
        if (host != "127.0.0.1:$port" && host != "localhost:$port") return HttpRes.error(403, "Not allowed.")
        val origin = req.headers["origin"]
        if (origin != null && origin !in ModelBridge.ORIGINS) return HttpRes.error(403, "Web pages may not use the model.")
        if (req.headers["x-too-big"] != null) return HttpRes.error(413, "The request is too big.")

        if (req.path == "/obsi" && req.method == "GET") {
            return HttpRes.json(200, buildJsonObject { put("app", env.app); put("ready", env.ready()); put("model", env.model()) })
        }
        if (req.path != "/v1/models" && req.path != "/v1/chat/completions") return HttpRes.error(404, "Not found.")

        val auth = req.headers["authorization"].orEmpty()
        val token = if (auth.startsWith("Bearer ", true)) auth.substring(7).trim() else ""
        if (token.isEmpty() || !env.allowed(token)) {
            delay(300) // Slows down guessing.
            return HttpRes.error(401, "This app is not linked. Link it again from the app that asks.")
        }

        if (req.path == "/v1/models") {
            if (req.method != "GET") return HttpRes.error(405, "Use GET.")
            return HttpRes.json(200, buildJsonObject {
                put("object", "list")
                put("data", buildJsonArray { add(buildJsonObject { put("id", "phone"); put("object", "model"); put("owned_by", env.app) }) })
            })
        }

        if (req.method != "POST") return HttpRes.error(405, "Use POST.")
        if (!env.ready()) return HttpRes.error(503, "There is no AI model on this phone yet. Get one in ${env.app}, Settings, AI.")
        val o = runCatching { Ai.json.parseToJsonElement(req.body).jsonObject }.getOrNull() ?: return HttpRes.error(400, "The request is not JSON.")
        val messages = (o["messages"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return HttpRes.error(400, "Send messages.")
        fun text(m: JsonObject): String = when (val c = m["content"]) {
            is JsonPrimitive -> c.contentOrNull.orEmpty()
            is JsonArray -> c.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
            else -> ""
        }
        val system = messages.filter { it["role"]?.jsonPrimitive?.contentOrNull == "system" }.joinToString("\n\n") { text(it) }
        val user = messages.filter { it["role"]?.jsonPrimitive?.contentOrNull != "system" }.joinToString("\n\n") { text(it) }
        if (user.isBlank()) return HttpRes.error(400, "Send a user message.")
        val max = ((o["max_tokens"] as? JsonPrimitive)?.intOrNull ?: 256).coerceIn(1, ModelBridge.MAX_TOKENS)

        val answer = try {
            askLock.withLock { env.ask(system, user, max) }
        } catch (e: Exception) {
            return HttpRes.error(503, e.message ?: "The model did not answer.")
        }
        env.used()
        return HttpRes.json(200, buildJsonObject {
            put("id", "obsi-" + System.currentTimeMillis())
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", "phone")
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject { put("role", "assistant"); put("content", answer) })
                    put("finish_reason", "stop")
                })
            })
        })
    }

    fun write(socket: Socket, res: HttpRes) {
        val body = res.body.toByteArray(Charsets.UTF_8)
        val reason = when (res.status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 413 -> "Payload Too Large"; else -> "Service Unavailable" }
        val head = "HTTP/1.1 ${res.status} $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
    }
}

/** The listening part. Start it once; it serves until [stop]. */
class BridgeServer(private val env: BridgeEnv, private val port: Int = ModelBridge.PORT) {
    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    val running: Boolean get() = server?.isClosed == false

    /** False when the port is taken, like by another Obsi app that lends its model already. */
    fun start(): Boolean {
        if (running) return true
        val s = runCatching { ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")) }.getOrNull() ?: return false
        server = s
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        sc.launch {
            while (isActive) {
                val client = try { s.accept() } catch (_: SocketException) { break }
                launch { serve(client) }
            }
        }
        return true
    }

    private suspend fun serve(client: Socket) {
        client.use { c ->
            runCatching {
                c.soTimeout = 15_000
                // Only this phone. The socket listens on 127.0.0.1, so this is a second lock.
                if (!c.inetAddress.isLoopbackAddress) return
                val req = BridgeHttp.read(c.getInputStream()) ?: return
                BridgeHttp.write(c, BridgeHttp.handle(req, env, port))
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        scope?.cancel()
        scope = null
    }
}

/** Keeps a job alive only while it is used: [touch] on each use, [onIdle] after [ms] without one. */
class IdleTimer(private val scope: CoroutineScope, private val ms: Long, private val onIdle: () -> Unit) {
    private var job: Job? = null
    fun touch() {
        job?.cancel()
        job = scope.launch { delay(ms); onIdle() }
    }
    fun cancel() { job?.cancel() }
}
