package app.obsi.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.obsi.core.data.ArticleDoc
import app.obsi.core.speech.ListenDoc
import app.obsi.core.speech.ListenSkip
import app.obsi.core.speech.ReadAloud

/**
 * Read aloud for one page of text in a [TranscriptView]: an article, a lesson's document, a PDF's text.
 * The page is read out loud, it keeps going with the screen off, the spoken word is lit and followed,
 * and scrolling away shows Sync. Every Obsi app with a page of text uses this one piece.
 *
 * Use: `val listen = rememberPageListen(id, title, source, built)`, call [attach] in the view's update,
 * put a Listen button on [listenHere], and draw [PageListenPlayer] over the page.
 */
class PageListen internal constructor(val engine: ReadAloud) {
    /** What is read, or null while the page is not built. */
    var doc: ListenDoc? = null
        internal set

    /** True while the voice is on this page, playing or paused. */
    var hearing by mutableStateOf(false)
        internal set

    /**
     * The small player shows: for this page, or for another page while its voice is playing.
     * Another page that is paused is not shown here, so a new page starts clean with its own Listen.
     */
    var barShown by mutableStateOf(false)
        internal set

    internal var view by mutableStateOf<TranscriptView?>(null)
    internal var paused = false
    internal var para = 0

    /** The controls sheet is open. */
    var showSheet by mutableStateOf(false)

    /** You scrolled away from the spoken word, so the page stopped following. */
    var away by mutableStateOf(false)

    /** The word the voice is on, as a word number on the page; -1 for none. */
    var heardWord by mutableIntStateOf(-1)
        internal set

    /** Listen from where you are reading. When it already reads this page, opens the controls. */
    fun listenHere() {
        val ld = doc ?: return
        val here = view?.paraAtTop() ?: 0
        when {
            // Paused, and you read on since: start where you are now, not where the voice stopped.
            hearing && paused && kotlin.math.abs(here - para) > 1 -> { away = false; engine.play(ld, here) }
            hearing -> showSheet = true
            else -> engine.play(ld, here)
        }
    }

    /** Hooks the page up: scrolling away, and Listen in the menu that shows when words are picked. */
    fun attach(tv: TranscriptView, page: app.obsi.core.data.TranscriptDoc) {
        view = tv
        tv.onAway = { away = it }
        tv.onListenFrom = { at ->
            val pi = page.paras.indexOfLast { it.charStart <= at }
            doc?.let { ld -> if (pi >= 0) { away = false; engine.play(ld, pi, at - page.paras[pi].charStart) } }
        }
    }

    /** Sync is offered: you scrolled away from the spoken word while it follows along. */
    val canSync: Boolean get() = away && hearing && engine.prefs.followAlong && heardWord >= 0

    /** Back to the spoken word. */
    fun sync() {
        view?.resync(heardWord)
        away = false
    }
}

/** Read aloud for the page [id], built as [built]. [title] and [source] are said and shown in the player. */
@Composable
fun rememberPageListen(id: String, title: String, source: String, built: ArticleDoc.Built?): PageListen {
    val ctx = LocalContext.current
    val pl = remember { PageListen(ReadAloud.get(ctx)) }
    val heard by pl.engine.ui.collectAsState()
    pl.doc = remember(built, id, title, source) { built?.let { ListenSkip.ofArticle(id, title, source, it) } }
    pl.hearing = heard.active && heard.docId == id
    pl.barShown = heard.active && (pl.hearing || heard.playing)
    pl.paused = !heard.playing
    pl.para = heard.para
    val hearing = pl.hearing
    val tv = pl.view
    LaunchedEffect(heard.para, heard.word, hearing, tv, built) {
        val view = tv ?: return@LaunchedEffect
        val page = built?.doc ?: return@LaunchedEffect
        val p = page.paras.getOrNull(heard.para)
        val r = heard.word
        if (!hearing || p == null || r == null) { view.setActive(-1); return@LaunchedEffect }
        val w = page.wordAtChar(p.charStart + r.first)
        if (w >= 0) {
            pl.heardWord = w
            view.setActive(if (pl.engine.prefs.highlightWords) w else -1)
            if (pl.engine.prefs.followAlong) view.follow(w)
        }
    }
    return pl
}

/** The small player over the page, and its controls sheet when open. Put it where the player should sit. */
@Composable
fun PageListenPlayer(listen: PageListen, modifier: Modifier = Modifier, showBar: Boolean = true) {
    if (showBar && listen.barShown) ListenBar(
        listen.engine, onOpen = { listen.showSheet = true }, modifier = modifier,
        onSync = if (listen.canSync) ({ listen.sync() }) else null,
    )
    if (listen.showSheet) ListenSheet(
        listen.engine, listen.doc, fromPara = { listen.view?.paraAtTop() ?: 0 }, onDismiss = { listen.showSheet = false },
    )
}
