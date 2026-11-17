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

/**
 * Loads an article's pictures after its text, a few at a time. The page is laid out again each time a
 * batch is in. A picture that fails is left out, so it leaves no empty box.
 */
@Composable
fun rememberPictures(built: ArticleDoc.Built?, imageWidth: Int): Map<Int, Bitmap?> {
    val ctx = LocalContext.current
    var pictures by remember(built) { mutableStateOf<Map<Int, Bitmap?>>(built?.images?.associate { it.first to null }.orEmpty()) }
    LaunchedEffect(built) {
        val list = built?.images ?: return@LaunchedEffect
        if (list.isEmpty()) return@LaunchedEffect
        val loader = coil3.SingletonImageLoader.get(ctx)
        val got = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()
        val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        coroutineScope {
            list.forEach { (at, url) ->
                launch(Dispatchers.IO) {
                    val req = coil3.request.ImageRequest.Builder(ctx).data(url).size(imageWidth).allowHardware(false).build()
                    val r = loader.execute(req)
                    val bmp = (r as? coil3.request.SuccessResult)?.image?.let { img -> runCatching { img.toBitmap() }.getOrNull() }
                    if (bmp != null) got[at] = bmp else failed += at
                }
            }
            // Show what has arrived every so often, so one slow picture does not hold the others back.
            launch {
                var shown = 0
                while (true) {
                    delay(500)
                    if (got.size != shown) { shown = got.size; pictures = list.associate { it.first to got[it.first] } }
                    if (shown + failed.size >= list.size) break
                }
            }
        }
        pictures = list.filter { it.first !in failed }.associate { it.first to got[it.first] }
    }
    return pictures
}

