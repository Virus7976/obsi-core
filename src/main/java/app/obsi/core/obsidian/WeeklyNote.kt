package app.obsi.core.obsidian

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.obsi.core.Brand
import app.obsi.core.data.Activity
import app.obsi.core.data.JsonFileStore
import app.obsi.core.share.SharedHighlights
import app.obsi.core.share.SharedSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields

/** Where your weekly notes are and how they are made. Shared by every Obsi app. */
@Serializable
data class WeeklySetup(
    val on: Boolean = false,
    /** The folder in the vault, like "Weekly". Blank: the top of the vault. */
    val folder: String = "",
    /** The note name, as a moment.js format, like "gggg-[W]ww". */
    val format: String = "gggg-[W]ww",
    /** The template for a new note, a path in the vault. Blank: a new note starts empty. */
    val template: String = "",
    /** The heading the apps' part goes under. Blank: the end of the note. */
    val heading: String = "",
    /** "locale", "monday" or "sunday". */
    val weekStart: String = "locale",
    /** Where the setup came from, for people: "Periodic Notes", "your notes", "you". */
    val foundBy: String = "",
    val updatedAt: Long = 0L,
) {
    val week: WeekFields get() = Moment.week(weekStart)
    fun name(day: LocalDate): String = Moment.format(Moment.startOfWeek(day, week), format, week)
    fun path(day: LocalDate): String = listOf(folder.trim('/'), name(day) + ".md").filter { it.isNotBlank() }.joinToString("/")
}

class WeeklyStore(context: Context) : JsonFileStore<WeeklySetup>(File(context.filesDir, "weekly.json")) {
    private val _state = MutableStateFlow(readOrNull() ?: WeeklySetup())
    val state: StateFlow<WeeklySetup> = _state.asStateFlow()
    val value: WeeklySetup get() = _state.value
    override fun encode(value: WeeklySetup) = WeeklyNote.json.encodeToString(WeeklySetup.serializer(), value)
    override fun decode(text: String) = WeeklyNote.json.decodeFromString(WeeklySetup.serializer(), text)

    fun update(block: (WeeklySetup) -> WeeklySetup) {
        _state.update { block(it).copy(updatedAt = System.currentTimeMillis()) }
        persist(_state.value)
    }

    internal fun adopt(c: WeeklySetup) {
        if (c.updatedAt <= _state.value.updatedAt) return
        _state.value = c
        persist(c)
    }
}

/**
 * Puts what you did this week, in every Obsi app, into your own weekly note. It works with any
 * weekly note setup: Periodic Notes, Calendar, Journals, Templater, or notes you make by hand. The
 * apps write only between two hidden markers, so the rest of the note stays yours. No AI.
 */
object WeeklyNote {
    internal val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val lock = Mutex()

    @Volatile private var store: WeeklyStore? = null
    fun store(context: Context): WeeklyStore = store ?: synchronized(this) { store ?: WeeklyStore(context.applicationContext).also { store = it } }

    // ---------- the vault ----------

    private fun root(context: Context): DocumentFile? {
        val tree = runCatching { SharedSetup.source?.invoke()?.folder }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(tree))
    }

    fun hasVault(context: Context): Boolean = root(context)?.canWrite() == true

    private fun dir(root: DocumentFile, path: String, create: Boolean): DocumentFile? {
        var d = root
        for (p in path.split('/').filter { it.isNotBlank() }) {
            d = d.findFile(p)?.takeIf { it.isDirectory } ?: (if (create) d.createDirectory(p) else null) ?: return null
        }
        return d
    }

    private fun file(root: DocumentFile, path: String): DocumentFile? {
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val d = dir(root, parts.dropLast(1).joinToString("/"), false) ?: return null
        return d.findFile(parts.last())?.takeIf { it.isFile }
    }

    private fun read(context: Context, f: DocumentFile): String? =
        runCatching { context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()

    private fun write(context: Context, f: DocumentFile, text: String) {
        context.contentResolver.openOutputStream(f.uri, "wt")!!.bufferedWriter().use { it.write(text) }
    }

    private fun readJson(context: Context, root: DocumentFile, path: String): JsonObject? =
        file(root, path)?.let { read(context, it) }?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    /** Folders in the vault, two levels down, for the folder picker. Obsidian's own folders are left out. */
    suspend fun folders(context: Context): List<String> = withContext(Dispatchers.IO) {
        val r = root(context) ?: return@withContext emptyList()
        val out = ArrayList<String>()
        fun walk(d: DocumentFile, path: String, depth: Int) {
            d.listFiles().filter { it.isDirectory && !it.name.orEmpty().startsWith(".") }.forEach { c ->
                val p = if (path.isEmpty()) c.name.orEmpty() else "$path/${c.name}"
                out += p
                if (depth < 2) walk(c, p, depth + 1)
            }
        }
        runCatching { walk(r, "", 1) }
        out.sortedBy { it.lowercase() }
    }

    /** Markdown files in the vault's template folders (or anywhere with "template" in the path), for the template picker. */
    suspend fun templates(context: Context): List<String> = withContext(Dispatchers.IO) {
        val r = root(context) ?: return@withContext emptyList()
        val roots = templateFolders(context, r).ifEmpty { listOf("Templates") }
        roots.flatMap { folder ->
            val d = dir(r, folder, false) ?: return@flatMap emptyList()
            val out = ArrayList<String>()
            fun walk(x: DocumentFile, path: String, depth: Int) {
                x.listFiles().forEach { c ->
                    val p = "$path/${c.name}"
                    if (c.isDirectory && depth < 3) walk(c, p, depth + 1)
                    else if (c.isFile && c.name.orEmpty().endsWith(".md")) out += p.trimStart('/')
                }
            }
            runCatching { walk(d, folder, 1) }
            out
        }.distinct().sortedWith(compareBy({ !it.contains("week", true) }, { it.lowercase() }))
    }

    private fun templateFolders(context: Context, r: DocumentFile): List<String> = listOfNotNull(
        readJson(context, r, ".obsidian/templates.json")?.str("folder"),
        readJson(context, r, ".obsidian/plugins/templater-obsidian/data.json")?.str("templates_folder"),
    ).map { it.trim('/') }.filter { it.isNotBlank() }.distinct()

    // ---------- finding your setup ----------

    /**
     * Reads the weekly note setup from the vault: Periodic Notes, Calendar or Journals, then checks it
     * against the notes really there. A folder that a plugin names but that has no notes is corrected to
     * the folder where the weekly notes are. A template is found by its name when none is set.
     */
    suspend fun detect(context: Context): WeeklySetup? = withContext(Dispatchers.IO) {
        val r = root(context) ?: return@withContext null
        var folder = ""
        var format = ""
        var template = ""
        var start = "locale"
        var by = ""
        readJson(context, r, ".obsidian/plugins/periodic-notes/data.json")?.let { o ->
            val w = o["weekly"] as? JsonObject
            if (w != null && (w["enabled"]?.jsonPrimitive?.contentOrNull == "true" || w.str("format").isNotBlank() || w.str("folder").isNotBlank())) {
                folder = w.str("folder"); format = w.str("format"); template = w.str("template"); by = "Periodic Notes"
            }
        }
        readJson(context, r, ".obsidian/plugins/calendar/data.json")?.let { o ->
            if (format.isBlank()) o.str("weeklyNoteFormat").takeIf { it.isNotBlank() }?.let { format = it; by = by.ifBlank { "Calendar" } }
            if (folder.isBlank()) folder = o.str("weeklyNoteFolder")
            if (template.isBlank()) template = o.str("weeklyNoteTemplate")
            o.str("weekStart").lowercase().takeIf { it == "monday" || it == "sunday" }?.let { start = it }
        }
        readJson(context, r, ".obsidian/plugins/journals/data.json")?.let { o ->
            // Journals keeps a list of journals; the first weekly one is used.
            val list = (o["journals"] as? JsonObject)?.values?.mapNotNull { it as? JsonObject }.orEmpty()
            list.firstOrNull { it.str("write").contains("week", true) || it.str("type") == "weekly" || (it["write"] as? JsonObject)?.str("type") == "week" }?.let { j ->
                if (format.isBlank()) format = j.str("nameTemplate").ifBlank { j.str("dateFormat") }
                if (folder.isBlank()) folder = j.str("folder")
                if (template.isBlank()) template = (j["templates"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty()
                by = by.ifBlank { "Journals" }
            }
        }
        if (format.isBlank()) format = "gggg-[W]ww"
        folder = folder.trim('/')

        // Check the folder against the notes in the vault.
        val pattern = Moment.regex(format)
        fun count(d: DocumentFile?) = d?.listFiles()?.count { it.isFile && pattern.matches(it.name.orEmpty().removeSuffix(".md")) } ?: 0
        if (count(dir(r, folder, false)) == 0) {
            val found = folders(context).map { it to count(dir(r, it, false)) }.filter { it.second > 0 }.maxByOrNull { it.second }
            if (found != null) { folder = found.first; if (by.isBlank()) by = "your notes" }
            else if (count(r) > 0) folder = ""
        }
        if (template.isNotBlank() && !template.endsWith(".md")) template += ".md"
        if (template.isNotBlank() && file(r, template) == null) template = ""
        if (template.isBlank()) template = templates(context).firstOrNull { it.substringAfterLast('/').contains("week", true) }.orEmpty()

        val base = WeeklySetup(folder = folder, format = format, template = template, weekStart = start, foundBy = by.ifBlank { "the usual setup" })
        // The heading: from the template, or from the newest weekly note.
        val sample = template.takeIf { it.isNotBlank() }?.let { file(r, it) }?.let { read(context, it) }
            ?: dir(r, folder, false)?.listFiles()?.filter { pattern.matches(it.name.orEmpty().removeSuffix(".md")) }?.maxByOrNull { it.lastModified() }?.let { read(context, it) }
            .orEmpty()
        base.copy(heading = WeeklyText.guessHeading(sample))
    }

    /** The headings the apps' part can go under: from the template, else from this week's or the newest note. */
    suspend fun headings(context: Context, s: WeeklySetup): List<String> = withContext(Dispatchers.IO) {
        val r = root(context) ?: return@withContext emptyList()
        val text = listOfNotNull(
            s.template.takeIf { it.isNotBlank() }?.let { file(r, it) },
            file(r, s.path(LocalDate.now())),
        ).firstNotNullOfOrNull { read(context, it) }.orEmpty()
        WeeklyText.headingNames(text)
    }

    // ---------- writing ----------

    sealed interface Result {
        data class Done(val path: String, val made: Boolean) : Result
        data object Nothing : Result
        data class Failed(val why: String) : Result
    }

    /** The newest setup from the other Obsi apps comes here, so you set it up once. */
    suspend fun pullFromOthers(context: Context) = withContext(Dispatchers.IO) {
        val newest = Brand.others.filter { it.installed(context) }.mapNotNull { other ->
            runCatching {
                context.contentResolver.query(Uri.parse("content://${SharedHighlights.authority(other)}/weekly"), null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) json.decodeFromString(WeeklySetup.serializer(), c.getString(0)) else null
                }
            }.getOrNull()
        }.maxByOrNull { it.updatedAt } ?: return@withContext
        store(context).adopt(newest)
    }

    internal fun cursor(context: Context): Cursor = MatrixCursor(arrayOf("json"), 1).apply {
        val c = store(context).value
        if (c.updatedAt > 0) addRow(arrayOf<Any>(json.encodeToString(WeeklySetup.serializer(), c)))
    }

    private fun prefs(c: Context) = c.getSharedPreferences("obsi_weekly", Context.MODE_PRIVATE)

    /** When you leave an app: at most once every 20 minutes, and only when it is on. Never throws. */
    suspend fun onLeave(context: Context) {
        val last = prefs(context).getLong("at", 0)
        if (System.currentTimeMillis() - last < 20 * 60_000L) return
        runCatching { pullFromOthers(context) }
        if (!store(context).value.on) return
        prefs(context).edit().putLong("at", System.currentTimeMillis()).apply()
        runCatching { update(context) }
    }

    /**
     * Writes this week's part. Last week's part is brought up to date too when its note has one, so
     * the last days of a week are never lost. A new note is made from your template only when there is
     * something to put in it.
     */
    suspend fun update(context: Context, day: LocalDate = LocalDate.now()): Result = withContext(Dispatchers.IO) {
        lock.withLock {
            val s = store(context).value
            val r = root(context) ?: return@withLock Result.Failed("Link your vault first.")
            val data = Activity.raw(context)
            val prev = day.minusWeeks(1)
            runCatching { writeWeek(context, r, s, data, prev, createNew = false) }
            runCatching { writeWeek(context, r, s, data, day, createNew = true) }.getOrElse { Result.Failed(it.message ?: "Could not write the note.") }
        }
    }

    private fun writeWeek(context: Context, r: DocumentFile, s: WeeklySetup, data: app.obsi.core.data.StatsData, day: LocalDate, createNew: Boolean): Result {
        val w = s.week
        val start = Moment.startOfWeek(day, w)
        val end = start.plusDays(6)
        val zone = ZoneId.systemDefault()
        val inWeek = { d: LocalDate -> !d.isBefore(start) && !d.isAfter(end) }

        val marks = data.highlights.filter { inWeek(Instant.ofEpochMilli(it.created).atZone(zone).toLocalDate()) }.sortedBy { it.created }
        val apps = data.days.map { (app, days) ->
            val week = days.filterKeys { k -> runCatching { inWeek(LocalDate.parse(k)) }.getOrDefault(false) }.values
            WeeklyText.AppWeek(app.title, week.sumOf { it.sec }, week.sumOf { it.finished }, marks.count { it.app == app })
        }
        val items = data.days.flatMap { (app, days) ->
            days.filterKeys { k -> runCatching { inWeek(LocalDate.parse(k)) }.getOrDefault(false) }.values
                .flatMap { it.items.entries }.groupBy { it.key }.map { (t, e) -> WeeklyText.ItemTime(t, app.title, e.sumOf { it.value }) }
        }
        val body = WeeklyText.body(apps, items, marks.map { WeeklyText.Mark(it.itemTitle, it.app.title, it.text, it.note) })

        val path = s.path(day)
        val existing = file(r, path)
        if (existing == null) {
            if (!createNew || body.isBlank()) return Result.Nothing
            val parts = path.split('/')
            val d = dir(r, parts.dropLast(1).joinToString("/"), true) ?: return Result.Failed("Could not make the folder ${s.folder}.")
            val tpl = s.template.takeIf { it.isNotBlank() }?.let { file(r, it) }?.let { read(context, it) }.orEmpty()
            val title = parts.last().removeSuffix(".md")
            val fresh = WeeklyText.render(tpl, title, start, w)
            val f = d.createFile("application/octet-stream", parts.last()) ?: return Result.Failed("Could not make the note.")
            write(context, f, WeeklyText.place(fresh, body, s.heading))
            return Result.Done(path, made = true)
        }
        val old = read(context, existing) ?: return Result.Failed("Could not read $path.")
        if (body.isBlank() && !old.contains(WeeklyText.START)) return Result.Nothing
        val new = WeeklyText.place(old, body.ifBlank { "*Nothing yet this week.*" }, s.heading)
        if (new != old) write(context, existing, new)
        return Result.Done(path, made = false)
    }
}
