package app.obsi.core.obsidian

object NoteLists {
    fun split(value: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        for (c in value) {
            when {
                c == '[' -> { depth++; cur.append(c) }
                c == ']' -> { depth = (depth - 1).coerceAtLeast(0); cur.append(c) }
                (c == ',' || c == '\n') && depth == 0 -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
