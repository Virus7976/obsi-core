package app.obsi.core.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf

/** How bouncy our own animations are. The Material components follow the motion scheme. */
data class Spring(val damping: Float, val stiffness: Float) {
    fun <T> spec(): FiniteAnimationSpec<T> = spring(damping, stiffness)
}

val LocalSpring = staticCompositionLocalOf { Spring(0.62f, 380f) }

fun springFor(motion: String) = when (motion) {
    "calm" -> Spring(0.9f, 420f)
    "playful" -> Spring(0.42f, 300f)
    else -> Spring(0.62f, 380f)
}
