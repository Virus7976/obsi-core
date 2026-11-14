package app.obsi.core

import android.content.Context
import app.obsi.core.data.AppSettings
import app.obsi.core.data.SettingsStore
import app.obsi.core.data.VaultStore
import app.obsi.core.update.SelfUpdate
import kotlinx.coroutines.flow.StateFlow

/** What the shared screens need from the app. Each app's Application class is one. */
interface ObsiHost {
    val settings: SettingsStore
    val vaults: VaultStore
    val updates: SelfUpdate
    /** Tags picked lately, newest first. */
    val recentTags: StateFlow<List<String>>

    /**
     * The template data of the video or article you used last, so the template editor shows what each
     * kind of data really holds. Empty when the library is empty. Called off the main thread.
     */
    fun sampleVariables(s: AppSettings): Map<String, String>

    /** The app icon choices. Each app has its own icons. */
    @androidx.compose.runtime.Composable
    fun IconPicker()

    /** Shows the lock screen player now. Only ObsiTube has one. */
    fun previewLockScreen(context: Context) {}
}
