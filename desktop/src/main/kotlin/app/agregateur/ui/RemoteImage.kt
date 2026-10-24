package app.agregateur.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image as SkiaImage

/** Affiches et fonds : cache disque d'OkHttp + petit cache mémoire des images décodées. */
class ImageLoader(private val http: OkHttpClient) {
    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MEMORY_ENTRIES
    }
    private val failed = HashSet<String>()

    fun cached(url: String?): ImageBitmap? = url?.let { synchronized(this) { memory[it] } }

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        if (synchronized(this) { url in failed }) return null
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) null
                    else SkiaImage.makeFromEncoded(body.bytes()).toComposeImageBitmap()
                }
            }.getOrNull()
        }
        synchronized(this) { if (bitmap != null) memory[url] = bitmap else failed += url }
        return bitmap
    }

    private companion object {
        const val MEMORY_ENTRIES = 240
    }
}

val LocalImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

/** Image distante ; `fallback` reste affiché tant qu'elle n'est pas là (ou si elle ne vient jamais). */
@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    val loader = LocalImageLoader.current
    val bitmap by produceState(loader?.cached(url), url, loader) {
        if (value == null && !url.isNullOrBlank() && loader != null) value = loader.load(url)
    }
    Box(modifier) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription, Modifier.matchParentSize(), contentScale = contentScale)
        } else {
            fallback()
        }
    }
}
