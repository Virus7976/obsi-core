package app.obsi.core.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Where one highlight is in its review schedule. A highlight with no card yet is new. */
@Serializable
data class ReviewCard(
    val id: String,
    val due: Long = 0L,
    /** How many times you kept it. The wait grows with each one. */
    val step: Int = 0,
    val dropped: Boolean = false,
)

/**
 * The review schedule. A highlight comes back after a day, then 3, 7, 16, 35, 90 and 180 days each time
 * you keep it. Dropping it only stops it coming back here. The highlight itself stays.
 */
open class ReviewCards(context: Context) : JsonFileStore<List<ReviewCard>>(File(context.filesDir, "review.json")) {
    private val _state = MutableStateFlow(readOrNull().orEmpty().associateBy { it.id })
    val cards: StateFlow<Map<String, ReviewCard>> = _state.asStateFlow()

    override fun encode(value: List<ReviewCard>) = storeJson.encodeToString(ListSerializer(ReviewCard.serializer()), value)
    override fun decode(text: String) = storeJson.decodeFromString(ListSerializer(ReviewCard.serializer()), text)

    private fun save() = persist(_state.value.values.toList())

    private fun put(card: ReviewCard) {
        _state.update { it + (card.id to card) }
        save()
    }

    /** When [id] is next due, or null when it was dropped. A new one waits a day from [createdAt]. */
    fun dueAt(id: String, createdAt: Long): Long? {
        val c = _state.value[id]
        if (c?.dropped == true) return null
        return c?.due ?: (createdAt + DAY)
    }

    fun keep(id: String, now: Long = System.currentTimeMillis()) {
        val step = (_state.value[id]?.step ?: 0)
        put(ReviewCard(id, now + DAYS[step.coerceAtMost(DAYS.lastIndex)] * DAY, step + 1))
    }

    /** Puts a card back the way it was. For Undo. A null [previous] means it had no card yet. */
    fun restore(id: String, previous: ReviewCard?) {
        _state.update { if (previous == null) it - id else it + (id to previous) }
        save()
    }

    /** Not now. Comes back tomorrow without counting as kept. */
    fun later(id: String, now: Long = System.currentTimeMillis()) {
        val c = _state.value[id]
        put(ReviewCard(id, now + DAY, c?.step ?: 0, c?.dropped ?: false))
    }

    fun drop(id: String) {
        val c = _state.value[id]
        put(ReviewCard(id, c?.due ?: 0L, c?.step ?: 0, dropped = true))
    }

    /** Does what another app asked: "keep", "later" or "drop". */
    fun act(id: String, action: String) = when (action) {
        "keep" -> keep(id)
        "drop" -> drop(id)
        else -> later(id)
    }

    companion object {
        const val DAY = 24L * 60 * 60 * 1000
        val DAYS = longArrayOf(1, 3, 7, 16, 35, 90, 180)
    }
}
