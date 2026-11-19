package app.obsi.core.ai

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.obsi.core.Brand
import app.obsi.core.data.HttpWords
import app.obsi.core.data.JsonFileStore
import app.obsi.core.share.SharedHighlights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** How a service is spoken to. Most speak the OpenAI chat API; Anthropic has its own. */
enum class AiStyle { OpenAi, Anthropic }

/**
 * The AI services the Obsi apps can use. You bring your own key. [defaultModel] is picked when the
 * service lists it; you can pick any other from the service's own list.
 */
enum class AiProvider(
    val id: String,
    val title: String,
    val blurb: String,
    val baseUrl: String,
    /** Where you make a key. */
    val keyPage: String,
    val style: AiStyle = AiStyle.OpenAi,
    val defaultModel: String = "",
    /** Models to prefer, in order, when [defaultModel] is not in the list. A model id that contains one wins. */
    val prefer: List<String> = emptyList(),
    val needsKey: Boolean = true,
    /** You type the address: your own server. */
    val needsUrl: Boolean = false,
    /** A model that turns speech into a transcript with word times, when the service has one. */
    val speechModel: String = "",
    /** What a key looks like, as a hint in the box. */
    val keyHint: String = "",
) {
    OpenRouter(
        "openrouter", "OpenRouter", "One key for models from every company. Pay as you go.",
        "https://openrouter.ai/api/v1", "https://openrouter.ai/settings/keys",
        defaultModel = "google/gemini-2.5-flash", prefer = listOf("gemini-2.5-flash", "claude-haiku", "gpt-5-mini"), keyHint = "sk-or-…",
    ),
    Anthropic(
        "anthropic", "Anthropic", "Claude models, from the company that makes them.",
        "https://api.anthropic.com/v1", "https://console.anthropic.com/settings/keys", AiStyle.Anthropic,
        defaultModel = "claude-opus-5-5", keyHint = "sk-ant-…",
    ),
    OpenAi(
        "openai", "OpenAI", "GPT models. Also makes transcripts.",
        "https://api.openai.com/v1", "https://platform.openai.com/api-keys",
        prefer = listOf("gpt-5-mini", "gpt-5", "gpt-4.1-mini", "gpt-4o-mini"), speechModel = "whisper-1", keyHint = "sk-…",
    ),
    Google(
        "google", "Google Gemini", "Gemini models. Has a free tier.",
        "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey",
        defaultModel = "gemini-2.5-flash", prefer = listOf("gemini-2.5-flash", "gemini-flash"), keyHint = "AIza…",
    ),
    Groq(
        "groq", "Groq", "Very fast open models. Also makes transcripts. Has a free tier.",
        "https://api.groq.com/openai/v1", "https://console.groq.com/keys",
        defaultModel = "llama-3.3-70b-versatile", prefer = listOf("llama-3.3-70b", "llama"), speechModel = "whisper-large-v3-turbo", keyHint = "gsk_…",
    ),
    Mistral(
        "mistral", "Mistral", "Models from Europe.",
        "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys",
        defaultModel = "mistral-small-latest", prefer = listOf("mistral-small", "mistral-medium"),
    ),
    DeepSeek(
        "deepseek", "DeepSeek", "Low-cost models.",
        "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys", defaultModel = "deepseek-chat", keyHint = "sk-…",
    ),
    XAi(
        "xai", "xAI", "Grok models.",
        "https://api.x.ai/v1", "https://console.x.ai", prefer = listOf("grok-3-mini", "grok"), keyHint = "xai-…",
    ),
    Ollama(
        "ollama", "Ollama", "Models on your own computer. Free and private. The computer must be on.",
        "http://192.168.1.10:11434/v1", "https://ollama.com/download", needsKey = false, needsUrl = true,
    ),
    Custom(
        "custom", "Other service", "Any service that speaks the OpenAI chat API, like LM Studio or your own server.",
        "", "", needsKey = false, needsUrl = true, speechModel = "whisper-1",
    );

    val makesTranscripts: Boolean get() = speechModel.isNotBlank()

    companion object {
        fun of(id: String): AiProvider? = entries.firstOrNull { it.id == id }
    }
}

/** Your AI setup. Kept per service, so a key stays when you try another service. */
@Serializable
data class AiConfig(
    /** The service the apps use. Blank: AI is off. */
    val provider: String = "",
    val keys: Map<String, String> = emptyMap(),
    val models: Map<String, String> = emptyMap(),
    val urls: Map<String, String> = emptyMap(),
    /** The service that makes transcripts. Blank: the same as [provider] when it can, else none. */
    val speechProvider: String = "",
    val updatedAt: Long = 0L,
) {
    val service: AiProvider? get() = AiProvider.of(provider)
    fun key(p: AiProvider): String = keys[p.id].orEmpty().trim()
    fun model(p: AiProvider): String = models[p.id].orEmpty().ifBlank { p.defaultModel }
    fun url(p: AiProvider): String = urls[p.id].orEmpty().ifBlank { p.baseUrl }.trim().trimEnd('/')

    /** A service picked and ready to use. */
    val ready: Boolean get() = service?.let { p -> (!p.needsKey || key(p).isNotBlank()) && (!p.needsUrl || url(p).isNotBlank()) && model(p).isNotBlank() } == true

    /** The service that makes transcripts, when there is one with a key. */
    val speech: AiProvider? get() {
        val p = AiProvider.of(speechProvider) ?: service?.takeIf { it.makesTranscripts }
        return p?.takeIf { it.makesTranscripts && (!it.needsKey || key(it).isNotBlank()) }
    }
}

/** A model a service offers. */
data class AiModel(val id: String, val name: String, val note: String = "")

class AiException(message: String) : Exception(message)

/** The AI setup on this phone, shared by every Obsi app. */
class AiStore(context: Context) : JsonFileStore<AiConfig>(File(context.filesDir, "ai.json")) {
    private val _state = MutableStateFlow(readOrNull() ?: AiConfig())
    val state: StateFlow<AiConfig> = _state.asStateFlow()
    val value: AiConfig get() = _state.value
    override fun encode(value: AiConfig) = Ai.json.encodeToString(AiConfig.serializer(), value)
    override fun decode(text: String) = Ai.json.decodeFromString(AiConfig.serializer(), text)

    fun update(block: (AiConfig) -> AiConfig) {
        _state.update { block(it).copy(updatedAt = System.currentTimeMillis()) }
        persist(_state.value)
    }

    /** Takes a newer setup from another Obsi app, as it is. */
    internal fun adopt(c: AiConfig) {
        if (c.updatedAt <= _state.value.updatedAt) return
        _state.value = c
        persist(c)
    }
}

object Ai {
    internal val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }

    @Volatile private var store: AiStore? = null
    fun store(context: Context): AiStore = store ?: synchronized(this) { store ?: AiStore(context.applicationContext).also { store = it } }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).callTimeout(6, TimeUnit.MINUTES).build()
    }

    // ---------- sharing between the apps ----------

    internal fun cursor(context: Context): Cursor = MatrixCursor(arrayOf("json"), 1).apply {
        val c = store(context).value
        if (c.updatedAt > 0) addRow(arrayOf<Any>(json.encodeToString(AiConfig.serializer(), c)))
    }

    /** The newest setup from the other Obsi apps comes here, so you set up AI once. */
    suspend fun pullFromOthers(context: Context) = withContext(Dispatchers.IO) {
        val newest = Brand.others.filter { it.installed(context) }.mapNotNull { other ->
            runCatching {
                context.contentResolver.query(Uri.parse("content://${SharedHighlights.authority(other)}/ai"), null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) json.decodeFromString(AiConfig.serializer(), c.getString(0)) else null
                }
            }.getOrNull()
        }.maxByOrNull { it.updatedAt } ?: return@withContext
        store(context).adopt(newest)
    }

    // ---------- the models a service offers ----------

    suspend fun models(c: AiConfig, p: AiProvider): List<AiModel> = withContext(Dispatchers.IO) {
        val base = c.url(p)
        if (base.isBlank()) throw AiException("Type the address of the service first.")
        val req = Request.Builder().url("$base/models").apply { auth(this, c, p) }.build()
        val body = call(req, p)
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            ?: json.parseToJsonElement(body).jsonObject["models"]?.jsonArray ?: JsonArray(emptyList())
        data.mapNotNull { it as? JsonObject }.mapNotNull { m ->
            val id = (m["id"] ?: m["name"])?.jsonPrimitive?.contentOrNull?.removePrefix("models/") ?: return@mapNotNull null
            // Speech, picture and embedding models cannot answer a question.
            if (listOf("whisper", "tts", "embed", "dall-e", "moderation", "image", "audio", "realtime", "transcribe", "guard").any { id.contains(it, true) }) return@mapNotNull null
            val name = (m["display_name"] ?: m["name"])?.jsonPrimitive?.contentOrNull?.takeIf { it != id }.orEmpty()
            AiModel(id, name.ifBlank { id }, note(m))
        }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    /** "$0.30 / $2.50 per 1M words in / out", when the service says (OpenRouter does). */
    private fun note(m: JsonObject): String {
        val price = m["pricing"] as? JsonObject ?: return ""
        fun per(k: String) = price[k]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.times(1_000_000)
        val inp = per("prompt") ?: return ""
        val out = per("completion") ?: return ""
        if (inp == 0.0 && out == 0.0) return "Free"
        fun f(d: Double) = if (d < 1) String.format(java.util.Locale.US, "$%.2f", d) else String.format(java.util.Locale.US, "$%.1f", d)
        return "${f(inp)} / ${f(out)}"
    }

    /** The model to use from a service's list: its default, else the first preferred one, else the first. */
    fun pickDefault(p: AiProvider, list: List<AiModel>): String? {
        if (list.isEmpty()) return null
        list.firstOrNull { it.id == p.defaultModel }?.let { return it.id }
        for (want in p.prefer) list.firstOrNull { it.id.contains(want, true) && !it.id.contains("preview", true) }?.let { return it.id }
        return list.first().id
    }

    // ---------- asking ----------

    /**
     * One question, one answer. [system] says how to answer, [user] is the question with its sources.
     * [fast] asks for a quick answer, for small jobs like picking a word. [jsonOut] asks for JSON only.
     */
    suspend fun ask(context: Context, system: String, user: String, fast: Boolean = false, jsonOut: Boolean = false, maxTokens: Int = 16000): String =
        withContext(Dispatchers.IO) {
            val c = store(context).value
            val p = c.service ?: throw AiException("Set up AI first: Settings, then AI.")
            if (!c.ready) throw AiException("Finish setting up AI: Settings, then AI.")
            when (p.style) {
                AiStyle.Anthropic -> anthropic(c, p, system, user, fast, maxTokens)
                AiStyle.OpenAi -> openAi(c, p, system, user, jsonOut, maxTokens)
            }.trim().let { if (jsonOut) stripFence(it) else it }
        }

    /** A short test, so the setup page can say it works. */
    suspend fun test(context: Context): String = ask(context, "Answer in at most five words.", "Say hello to a new user of a learning app.", fast = true, maxTokens = 2000)

    private fun openAi(c: AiConfig, p: AiProvider, system: String, user: String, jsonOut: Boolean, maxTokens: Int): String {
        val model = c.model(p)
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            // OpenAI's newer models take only max_completion_tokens. The others take max_tokens.
            if (p == AiProvider.OpenAi) put("max_completion_tokens", maxTokens) else put("max_tokens", maxTokens)
            if (jsonOut && p in setOf(AiProvider.OpenAi, AiProvider.OpenRouter, AiProvider.Groq, AiProvider.Google, AiProvider.Mistral, AiProvider.DeepSeek, AiProvider.XAi)) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }
        val req = Request.Builder().url(c.url(p) + "/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { auth(this, c, p) }.build()
        val o = json.parseToJsonElement(call(req, p)).jsonObject
        val choice = o["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw AiException("${p.title} sent an empty answer. Try again.")
        val content = choice["message"]?.jsonObject?.get("content")
        val text = when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("")
            else -> ""
        }
        if (text.isBlank()) throw AiException("${p.title} sent an empty answer. Try again, or pick another model.")
        return text
    }

    private fun anthropic(c: AiConfig, p: AiProvider, system: String, user: String, fast: Boolean, maxTokens: Int): String {
        val model = c.model(p)
        val current = listOf("claude-opus-5", "claude-sonnet-5", "claude-haiku-5", "claude-fable", "claude-mythos").any { model.startsWith(it) }
        // These models can decline a request; with fallbacks the service retries on a model that can answer.
        val fallbacks = listOf("claude-fable-5-2", "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5").any { model == it }
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", user) }) })
            // Thinking is left to the model. Effort says how hard to think: low for small jobs.
            if (current) put("output_config", buildJsonObject { put("effort", if (fast) "low" else "medium") })
            if (fallbacks) put("fallbacks", "default")
        }
        val req = Request.Builder().url(c.url(p) + "/messages")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply {
                auth(this, c, p)
                if (fallbacks) header("anthropic-beta", "server-side-fallback-2026-07-01")
            }.build()
        val o = json.parseToJsonElement(call(req, p)).jsonObject
        if (o["stop_reason"]?.jsonPrimitive?.contentOrNull == "refusal") throw AiException("Claude would not answer that. Ask it another way.")
        val text = o["content"]?.jsonArray.orEmpty().mapNotNull { b ->
            val block = b as? JsonObject ?: return@mapNotNull null
            if (block["type"]?.jsonPrimitive?.contentOrNull == "text") block["text"]?.jsonPrimitive?.contentOrNull else null
        }.joinToString("")
        if (text.isBlank()) throw AiException("Claude sent an empty answer. Try again.")
        return text
    }

    private fun auth(b: Request.Builder, c: AiConfig, p: AiProvider) {
        val key = c.key(p)
        when (p.style) {
            AiStyle.Anthropic -> { b.header("x-api-key", key); b.header("anthropic-version", "2023-06-01") }
            AiStyle.OpenAi -> if (key.isNotBlank()) b.header("Authorization", "Bearer $key")
        }
        if (p == AiProvider.OpenRouter) { b.header("HTTP-Referer", "https://github.com/Virus7976"); b.header("X-Title", "Obsi apps") }
    }

    private fun call(req: Request, p: AiProvider): String = try {
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.isSuccessful) return text
            val said = runCatching {
                val e = json.parseToJsonElement(text).jsonObject["error"]
                (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: (e as? JsonPrimitive)?.contentOrNull
            }.getOrNull().orEmpty().take(200)
            throw AiException(
                when (r.code) {
                    401, 403 -> "${p.title} did not take this key. Check it, or make a new one."
                    402 -> "${p.title} says the account has no credit left."
                    404 -> if (said.contains("model", true)) "${p.title} does not have that model. Pick another." else HttpWords.say(404, p.title)
                    429 -> "${p.title} says: too many requests, or no credit left. Wait a minute, or check the account."
                    400 -> if (said.isNotBlank()) "${p.title}: $said" else HttpWords.say(400, p.title)
                    else -> HttpWords.say(r.code, p.title)
                },
            )
        }
    } catch (e: AiException) { throw e } catch (e: Exception) { throw AiException(HttpWords.error(e, "Could not reach ${p.title}. Try again.")) }

    /** Some models wrap JSON in a code fence. */
    fun stripFence(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return t
        return t.substringAfter('\n').substringBeforeLast("```").trim()
    }

    /** The first JSON object in a text, for models that talk before it. */
    fun jsonObject(s: String): JsonObject? {
        val t = stripFence(s)
        runCatching { return json.parseToJsonElement(t).jsonObject }
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { json.parseToJsonElement(t.substring(a, b + 1)).jsonObject }.getOrNull()
    }

    internal fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.longOrNull
}
