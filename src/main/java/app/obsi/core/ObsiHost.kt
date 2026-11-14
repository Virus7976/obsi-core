package app.obsi.core

import app.obsi.core.data.VaultStore
import kotlinx.coroutines.flow.StateFlow

/** What the shared screens need from the app. Each app's Application class is one. */
interface ObsiHost {
    val vaults: VaultStore
    /** Tags picked lately, newest first. */
    val recentTags: StateFlow<List<String>>
}
