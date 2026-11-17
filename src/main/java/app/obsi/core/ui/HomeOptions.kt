package app.obsi.core.ui

import app.obsi.core.Brand
import app.obsi.core.ObsiApp

/** The tabs Home can show, by id. ObsiTube also has the channel feed. ObsiLearn has courses. */
val HomeTabs: Map<String, String>
    get() = if (Brand.app == ObsiApp.Learn) linkedMapOf("library" to "Courses", "files" to "Files")
    else if (Brand.app == ObsiApp.Tube) linkedMapOf("library" to "Library", "feed" to "Feed", "queue" to "Queue")
    else linkedMapOf("library" to "Library", "queue" to "Up next")

val LibraryLayouts = listOf("list" to "List", "grid" to "Grid", "compact" to "Compact")

/** The little figure that runs while Home pulls to refresh. */
val PixelChars = listOf("ghost" to "Ghost", "heart" to "Heart", "star" to "Star", "invader" to "Invader", "off" to "None")
