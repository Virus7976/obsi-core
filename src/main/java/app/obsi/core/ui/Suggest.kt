package app.obsi.core.ui

/** Names you already use, best match first. Typed letters may skip some. An empty box shows them all. */
fun suggestFor(all: List<String>, typed: String, max: Int = 12): List<String> {
    val q = typed.trim().replace(" ", "")
    val pool = all.filterNot { it.equals(typed.trim(), true) }
    if (q.isEmpty()) return pool.take(max)
    return pool.mapNotNull { n -> app.obsi.core.data.Omni.fuzzy(n, q)?.let { n to it.score } }
        .sortedByDescending { it.second }.map { it.first }.take(max)
}
