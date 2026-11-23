package app.obsi.core.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.obsi.core.data.ArticleDoc
import coil3.toBitmap
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An article's pictures, by their place in the text. Null until the first set is ready: the page waits for it
 * (a short time at most), so it is laid out once with its pictures in. Every picture that comes in later is
 * added in one more step at the end. On a long page each step costs the phone a lot, so there are at most two.
 * A picture that fails is left out, so it leaves no empty box. A picture still loading is a grey box.
 */
@Composable
fun rememberPicturesReady(built: ArticleDoc.Built?, imageWidth: Int, waitMs: Long = 900): Map<Int, Bitmap?>? {
    val ctx = LocalContext.current
    var pictures by remember(built) {
        mutableStateOf<Map<Int, Bitmap?>?>(if (built != null && built.images.isEmpty()) emptyMap() else null)
    }
    LaunchedEffect(built) {
        val list = built?.images ?: return@LaunchedEffect
        if (list.isEmpty()) return@LaunchedEffect
        val loader = coil3.SingletonImageLoader.get(ctx)
        val got = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()
        val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        fun now() = list.filter { it.first !in failed }.associate { it.first to got[it.first] }
        coroutineScope {
            list.forEach { (at, url) ->
                launch(Dispatchers.IO) {
                    val req = coil3.request.ImageRequest.Builder(ctx).data(url).size(imageWidth).allowHardware(false).build()
                    val r = loader.execute(req)
                    val bmp = (r as? coil3.request.SuccessResult)?.image?.let { img -> runCatching { img.toBitmap() }.getOrNull() }
                    if (bmp != null) got[at] = bmp else failed += at
                }
            }
            launch {
                // Saved pictures come off the phone in a moment. Wait for them, but not for a slow network.
                withTimeoutOrNull(waitMs) { while (got.size + failed.size < list.size) delay(25) }
                val first = now()
                pictures = first
                while (got.size + failed.size < list.size) delay(250)
                val last = now()
                if (last.keys != first.keys || last.any { (k, v) -> first[k] !== v }) pictures = last
            }
        }
    }
    return pictures
}

/** [rememberPicturesReady] for a page that does not wait: grey boxes until the pictures are in. */
@Composable
fun rememberPictures(built: ArticleDoc.Built?, imageWidth: Int): Map<Int, Bitmap?> =
    rememberPicturesReady(built, imageWidth, waitMs = 0) ?: built?.images?.associate { it.first to null }.orEmpty()
