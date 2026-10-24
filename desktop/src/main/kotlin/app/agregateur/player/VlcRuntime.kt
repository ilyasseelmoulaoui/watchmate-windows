package app.agregateur.player

import app.agregateur.core.playback.SubtitleBackground
import app.agregateur.core.playback.SubtitleColor
import app.agregateur.core.playback.SubtitleStyle
import com.sun.jna.NativeLibrary
import java.io.File
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery

/**
 * Où trouver libVLC : d'abord la copie embarquée dans les ressources de l'application (`vlc/`, voir la tâche
 * Gradle `fetchVlc`), sinon un VLC 64 bits installé sur la machine.
 */
object VlcRuntime {
    @Volatile private var located: Boolean? = null

    val available: Boolean
        get() = located ?: synchronized(this) { located ?: locate().also { located = it } }

    private fun locate(): Boolean {
        val bundled = System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }
            ?: File("resources/windows/vlc").takeIf { it.isDirectory }
            ?: File("desktop/resources/windows/vlc")
        if (File(bundled, "libvlc.dll").isFile) {
            NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), bundled.absolutePath)
            NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), bundled.absolutePath)
            // libvlccore d'abord : Windows ne cherche pas les dépendances d'une DLL dans son propre dossier.
            if (runCatching { NativeLibrary.getInstance(RuntimeUtil.getLibVlcCoreLibraryName()) }.isSuccess) return true
        }
        return runCatching { NativeDiscovery().discover() }.getOrDefault(false)
    }

    /**
     * Les sous-titres intégrés au fichier sont dessinés par VLC : leur apparence se règle à la création. Les
     * sous-titres des modules, eux, sont dessinés par l'application et suivent les réglages en direct.
     */
    fun newFactory(style: SubtitleStyle): MediaPlayerFactory = MediaPlayerFactory(
        "--quiet",
        "--no-video-title-show",
        "--no-snapshot-preview",
        "--no-osd",
        "--no-stats",
        "--avcodec-hw=any",
        "--sub-text-scale=${style.sizePercent}",
        "--freetype-color=${vlcColor(style.color)}",
        "--freetype-outline-thickness=${if (style.outline) 4 else 0}",
        "--freetype-background-opacity=${
            when (style.background) {
                SubtitleBackground.NONE -> 0
                SubtitleBackground.TRANSLUCENT -> 140
                SubtitleBackground.OPAQUE -> 255
            }
        }",
        "--sub-margin=${style.bottomPercent * 10}",
    )

    private fun vlcColor(color: SubtitleColor): Int = when (color) {
        SubtitleColor.WHITE -> 0xFFFFFF
        SubtitleColor.YELLOW -> 0xFFE15A
        SubtitleColor.CYAN -> 0x7FE3F0
        SubtitleColor.GREEN -> 0x9BE58C
        SubtitleColor.PINK -> 0xFF7A9C
    }
}
