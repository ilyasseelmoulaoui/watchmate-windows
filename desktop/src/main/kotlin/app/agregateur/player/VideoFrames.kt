package app.agregateur.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import app.agregateur.core.playback.VideoFit
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

/**
 * Images décodées par VLC, recopiées pour Compose : la vidéo est dessinée comme le reste de l'interface, et les
 * commandes peuvent passer par-dessus (une fenêtre native ne le permettrait pas). Au-delà de [MAX_WIDTH] ×
 * [MAX_HEIGHT], VLC réduit lui-même l'image : la copie reste légère, même pour une source 4K.
 */
class VideoFrames : BufferFormatCallback, RenderCallback {
    private val lock = Any()
    private var info: ImageInfo? = null
    private var pixels = ByteArray(0)

    /** Change à chaque image : le dessin le lit, il est donc refait à chaque nouvelle image. */
    val frame = mutableLongStateOf(0L)

    @Volatile var hasVideo = false
        private set

    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
        val scale = min(1.0, min(MAX_WIDTH.toDouble() / sourceWidth, MAX_HEIGHT.toDouble() / sourceHeight))
        val width = max(2, (sourceWidth * scale).roundToInt() and 1.inv())
        val height = max(2, (sourceHeight * scale).roundToInt() and 1.inv())
        synchronized(lock) {
            info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            pixels = ByteArray(width * height * 4)
        }
        return RV32BufferFormat(width, height)
    }

    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit

    override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
        synchronized(lock) {
            val buffer = nativeBuffers[0]
            buffer.rewind()
            if (buffer.remaining() >= pixels.size) buffer.get(pixels)
        }
        hasVideo = true
        frame.longValue++
    }

    fun clear() {
        hasVideo = false
        frame.longValue++
    }

    @Composable
    fun Surface(fit: VideoFit, modifier: Modifier = Modifier) {
        Canvas(modifier) {
            frame.longValue
            if (!hasVideo) return@Canvas
            val image = synchronized(lock) {
                val current = info ?: return@Canvas
                Image.makeRaster(current, pixels, current.width * 4)
            }
            image.use {
                val src = Rect.makeWH(it.width.toFloat(), it.height.toFloat())
                val dst = target(it.width.toFloat(), it.height.toFloat(), size.width, size.height, fit)
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.clipRect(Rect.makeWH(size.width, size.height))
                    canvas.nativeCanvas.drawImageRect(it, src, dst, SAMPLING, null, true)
                    canvas.nativeCanvas.restore()
                }
            }
        }
    }

    private fun target(w: Float, h: Float, boxW: Float, boxH: Float, fit: VideoFit): Rect {
        if (fit == VideoFit.STRETCH) return Rect.makeWH(boxW, boxH)
        val scale = if (fit == VideoFit.ZOOM) max(boxW / w, boxH / h) else min(boxW / w, boxH / h)
        val dw = w * scale
        val dh = h * scale
        return Rect.makeXYWH((boxW - dw) / 2f, (boxH - dh) / 2f, dw, dh)
    }

    private companion object {
        const val MAX_WIDTH = 2560
        const val MAX_HEIGHT = 1440
        val SAMPLING = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)
    }
}
