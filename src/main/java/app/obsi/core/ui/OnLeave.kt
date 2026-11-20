package app.obsi.core.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Quiet work done when you open and leave an Obsi app. Opening takes in setup shared by the other
 * apps; leaving brings the weekly note and the home-screen widget up to date. Put once in each
 * app, inside its theme.
 */
@Composable
fun ObsiOnLeave() {
    val ctx = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> OnLeave.arrive(ctx)
                Lifecycle.Event.ON_STOP -> OnLeave.run(ctx)
                else -> {}
            }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
}

object OnLeave {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** When you open an app: the AI and weekly note setup made in another Obsi app comes here. */
    fun arrive(context: Context) {
        scope.launch {
            runCatching { app.obsi.core.ai.Ai.pullFromOthers(context) }
            // What the phone can run may have changed: Gemini Nano arrived, or another app downloaded a model.
            runCatching { app.obsi.core.ai.OnPhone.check(context); app.obsi.core.ai.OnPhone.watch(context) }
            runCatching { app.obsi.core.obsidian.WeeklyNote.pullFromOthers(context) }
        }
    }

    fun run(context: Context) {
        scope.launch {
            runCatching { app.obsi.core.obsidian.WeeklyNote.onLeave(context) }
            runCatching { app.obsi.core.widget.HighlightWidget.refresh(context) }
        }
    }
}
