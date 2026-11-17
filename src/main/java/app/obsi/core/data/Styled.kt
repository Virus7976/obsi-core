package app.obsi.core.data

/** A part of the page text that is drawn in its own way: a heading, a link, a quote, a picture and the like. */
data class Styled(val range: IntRange, val kind: String, val href: String = "", val level: Int = 0) {
    companion object {
        /** Stands in the text where a picture goes. */
        const val IMAGE_CHAR = '\uFFFC'
    }
}
