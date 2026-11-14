package app.obsi.core.obsidian

import app.obsi.core.data.TemplateProperty

data class BuiltProperty(val property: TemplateProperty, val type: String, val value: String)

data class BuiltNote(
    val vault: String,
    val folder: String,
    val name: String,
    val behavior: String,
    val properties: List<BuiltProperty>,
    val content: String,
    val markdown: String,
) {
    val path: String get() = if (folder.isBlank()) name else "${folder.trim('/')}/$name"
}
