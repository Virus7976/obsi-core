package app.obsi.core.link

import android.content.Context
import app.obsi.core.ObsiApp

/**
 * Finds a Bible verse named in text, like "John 3:16", "1 Cor 13:4-7" or "Psalm 23", so ObsiTube
 * and ObsiRead can open it in Obsi Bible. Obsi Bible reads the words itself, so this only has to
 * find them.
 */
object VerseRefs {
    private val books = listOf(
        "Genesis", "Gen", "Exodus", "Exod", "Ex", "Leviticus", "Lev", "Numbers", "Num", "Deuteronomy", "Deut",
        "Joshua", "Josh", "Judges", "Judg", "Ruth", "Samuel", "Sam", "Kings", "Kgs", "Chronicles", "Chron", "Chr",
        "Ezra", "Nehemiah", "Neh", "Esther", "Esth", "Job", "Psalms", "Psalm", "Ps", "Psa", "Proverbs", "Prov",
        "Ecclesiastes", "Eccl", "Eccles", "Song of Solomon", "Song of Songs", "Song", "Isaiah", "Isa", "Jeremiah", "Jer",
        "Lamentations", "Lam", "Ezekiel", "Ezek", "Daniel", "Dan", "Hosea", "Hos", "Joel", "Amos", "Obadiah", "Obad",
        "Jonah", "Micah", "Mic", "Nahum", "Nah", "Habakkuk", "Hab", "Zephaniah", "Zeph", "Haggai", "Hag",
        "Zechariah", "Zech", "Malachi", "Mal", "Matthew", "Matt", "Mt", "Mark", "Mk", "Luke", "Lk", "John", "Jn",
        "Acts", "Romans", "Rom", "Corinthians", "Cor", "Galatians", "Gal", "Ephesians", "Eph", "Philippians", "Phil",
        "Colossians", "Col", "Thessalonians", "Thess", "Timothy", "Tim", "Titus", "Philemon", "Phlm", "Hebrews", "Heb",
        "James", "Jas", "Peter", "Pet", "Jude", "Revelation", "Rev",
    ).sortedByDescending { it.length }

    private val pattern: Regex = run {
        val names = books.joinToString("|") { it.replace(" ", """\s+""") }
        // A verse needs chapter:verse. A chapter alone counts only for a psalm, since "Mark 2" may be a name.
        Regex(
            """(?<![\p{L}])((?:[1-3]|I{1,3})\s?)?(""" + names + """)\.?\s?(\d{1,3})(?::(\d{1,3})(?:\s?[-–]\s?(\d{1,3}))?)?(?![\p{L}\d])""",
            RegexOption.IGNORE_CASE,
        )
    }

    /** The first verse in [text], as it was written ("John 3:16"), or null. */
    fun find(text: String): String? = pattern.findAll(text).firstOrNull { m ->
        val hasVerse = m.groupValues[4].isNotEmpty()
        val book = m.groupValues[2].lowercase()
        // "Job 3" and "Acts 2" read as words too; ask for a verse there as well.
        hasVerse || book.startsWith("ps")
    }?.value?.trim()

    /** Opens [verse] in Obsi Bible, or the page to get it. */
    fun open(context: Context, verse: String) = Links.handOff(context, ObsiApp.Bible, verse)
}
