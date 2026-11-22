package app.obsi.core

import app.obsi.core.ai.BridgeEnv
import app.obsi.core.ai.BridgeHttp
import app.obsi.core.ai.BridgeServer
import app.obsi.core.ai.HttpReq
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelBridgeTest {
    private class Fake(var isReady: Boolean = true) : BridgeEnv {
        override val app = "ObsiTube"
        var asked: Triple<String, String, Int>? = null
        override fun ready() = isReady
        override fun model() = "Qwen 2.5, small"
        override fun allowed(token: String) = token == "good-token-good-token-good-token"
        override suspend fun ask(system: String, user: String, maxTokens: Int): String {
            asked = Triple(system, user, maxTokens)
            return "ready"
        }
    }

    private val host = mapOf("host" to "127.0.0.1:47391")
    private val auth = host + ("authorization" to "Bearer good-token-good-token-good-token")
    private val chat = """{"model":"phone","max_tokens":9000,"messages":[{"role":"system","content":"Be short."},{"role":"user","content":"Are you there?"}]}"""

    private fun call(req: HttpReq, env: BridgeEnv = Fake()) = runBlocking { BridgeHttp.handle(req, env) }

    @Test fun presence_needs_no_key_and_shows_no_text() {
        val r = call(HttpReq("GET", "/obsi", host, ""))
        assertEquals(200, r.status)
        assertTrue(r.body.contains("\"app\":\"ObsiTube\""))
        assertTrue(r.body.contains("\"ready\":true"))
    }

    @Test fun the_model_needs_the_key() {
        assertEquals(401, call(HttpReq("POST", "/v1/chat/completions", host, chat)).status)
        assertEquals(401, call(HttpReq("POST", "/v1/chat/completions", host + ("authorization" to "Bearer wrong"), chat)).status)
        assertEquals(401, call(HttpReq("GET", "/v1/models", host, "")).status)
    }

    @Test fun a_linked_app_gets_an_answer_and_the_tokens_are_capped() {
        val env = Fake()
        val r = call(HttpReq("POST", "/v1/chat/completions", auth, chat), env)
        assertEquals(200, r.status)
        assertTrue(r.body.contains("\"content\":\"ready\""))
        assertEquals("Be short.", env.asked?.first)
        assertEquals("Are you there?", env.asked?.second)
        assertEquals(512, env.asked?.third)
    }

    @Test fun web_pages_and_other_host_names_are_refused() {
        assertEquals(403, call(HttpReq("POST", "/v1/chat/completions", auth + ("origin" to "https://evil.example"), chat)).status)
        assertEquals(403, call(HttpReq("GET", "/obsi", mapOf("host" to "evil.example:47391"), "")).status)
        assertEquals(200, call(HttpReq("POST", "/v1/chat/completions", auth + ("origin" to "app://obsidian.md"), chat)).status)
    }

    @Test fun no_model_says_so() {
        assertEquals(503, call(HttpReq("POST", "/v1/chat/completions", auth, chat), Fake(isReady = false)).status)
    }

    @Test fun broken_or_big_requests_are_refused() {
        assertEquals(400, call(HttpReq("POST", "/v1/chat/completions", auth, "not json")).status)
        val big = "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:47391\r\nContent-Length: 999999\r\n\r\n"
        val req = BridgeHttp.read(ByteArrayInputStream(big.toByteArray()))!!
        assertEquals(413, call(req).status)
        val endless = ByteArrayInputStream(("GET / HTTP/1.1\r\n" + "X: " + "a".repeat(20_000)).toByteArray())
        assertEquals(null, BridgeHttp.read(endless))
    }

    @Test fun it_answers_over_a_real_socket() {
        val server = BridgeServer(Fake(), port = 47398)
        assertTrue(server.start())
        try {
            val c = URL("http://127.0.0.1:47398/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer good-token-good-token-good-token")
            c.outputStream.use { it.write(chat.toByteArray()) }
            assertEquals(200, c.responseCode)
            assertTrue(c.inputStream.bufferedReader().readText().contains("ready"))
            // A second server on the same port says no, so two apps never fight over it.
            assertFalse(BridgeServer(Fake(), port = 47398).start())
        } finally {
            server.stop()
        }
    }
}
