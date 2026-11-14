package app.obsi.core

import app.obsi.core.data.AppSettings
import app.obsi.core.data.SettingsStore
import app.obsi.core.data.VaultStore
import kotlinx.coroutines.flow.StateFlow

/** What the shared screens need from the app. Each app's Application class is one. */
interface ObsiHost {
    val settings: SettingsStore
    val vaults: VaultStore
    /** Tags picked lately, newest first. */
    val recentTags: StateFlow<List<String>>

    /**
     * The template data of the video or article you used last, so the template editor shows what each
     * kind of data really holds. Empty when the library is empty. Called off the main thread.
     */
    fun sampleVariables(s: AppSettings): Map<String, String>
}
