package app.obsi.core.ai

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.security.MessageDigest
import java.security.SecureRandom

/** An app the person allowed to use the phone's model, like the Clips plugin in Obsidian. */
@Serializable
data class ModelLink(val name: String, val hash: String, val added: Long, val used: Long = 0)

/**
 * The apps allowed to use the phone's model. Only a hash of each key is kept, so the key itself
 * lives only in the app that holds it. Removing a link stops that key at once.
 */
object ModelLinks {
    private val _links = MutableStateFlow<List<ModelLink>>(emptyList())
    val links: StateFlow<List<ModelLink>> = _links.asStateFlow()
    private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("obsi_model_links", Context.MODE_PRIVATE)

    fun load(c: Context): List<ModelLink> {
        if (!loaded) {
            _links.value = runCatching { Ai.json.decodeFromString(ListSerializer(ModelLink.serializer()), prefs(c).getString("links", "[]") ?: "[]") }.getOrDefault(emptyList())
            loaded = true
        }
        return _links.value
    }

    private fun save(c: Context, list: List<ModelLink>) {
        _links.value = list
        prefs(c).edit().putString("links", Ai.json.encodeToString(ListSerializer(ModelLink.serializer()), list)).apply()
    }

    /** Makes a new key for [name] and keeps its hash. The key is shown to the asking app once. */
    fun add(c: Context, name: String): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val clean = cleanName(name)
        // One link per app name: linking again replaces the old key.
        save(c, load(c).filter { it.name != clean } + ModelLink(clean, hash(token), System.currentTimeMillis()))
        return token
    }

    fun remove(c: Context, link: ModelLink) = save(c, load(c).filter { it.hash != link.hash })

    fun allowed(c: Context, token: String): Boolean {
        val h = hash(token).toByteArray()
        // Compares every link in full, in the same time, so timing gives nothing away.
        var ok = false
        for (l in load(c)) if (MessageDigest.isEqual(h, l.hash.toByteArray())) ok = true
        return ok
    }

    fun markUsed(c: Context, token: String) {
        val h = hash(token)
        val list = load(c)
        if (list.none { it.hash == h }) return
        save(c, list.map { if (it.hash == h) it.copy(used = System.currentTimeMillis()) else it })
    }

    fun cleanName(name: String): String = name.filter { it.isLetterOrDigit() || it in " .-_" }.trim().take(30).ifBlank { "An app" }

    fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
