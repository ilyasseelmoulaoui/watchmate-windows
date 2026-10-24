package app.agregateur.core

import app.agregateur.core.playback.PlayerControls
import app.agregateur.core.playback.SubtitleStyle
import app.agregateur.core.playback.VideoFit
import app.agregateur.core.settings.FileSettingsStore
import app.agregateur.core.settings.Settings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class PlayerControlsTest {
    @Test
    fun subtitleDelayIsClampedToOneMinute() {
        assertEquals(500L, PlayerControls.stepDelay(0, 500))
        assertEquals(-60_000L, PlayerControls.stepDelay(-59_900, -1_000))
        assertEquals(60_000L, PlayerControls.stepDelay(59_500, 1_000))
    }

    @Test
    fun seekStaysInsideTheVideo() {
        assertEquals(0L, PlayerControls.seekBy(4_000, -10_000, 100_000))
        assertEquals(100_000L, PlayerControls.seekBy(95_000, 10_000, 100_000))
        assertEquals(25_000L, PlayerControls.seekBy(15_000, 10_000, 0))
    }

    @Test
    fun fullSwipeSeeksNinetySeconds() {
        assertEquals(190_000L, PlayerControls.swipeSeekTarget(100_000, 1f, 3_600_000))
        assertEquals(55_000L, PlayerControls.swipeSeekTarget(100_000, -0.5f, 3_600_000))
    }

    @Test
    fun levelsStayBetweenZeroAndOne() {
        assertEquals(1f, PlayerControls.adjustLevel(0.8f, 0.5f))
        assertEquals(0f, PlayerControls.adjustLevel(0.2f, -0.5f))
        assertEquals(0.5f, PlayerControls.adjustLevel(0.25f, 0.25f))
    }

    @Test
    fun fitCyclesThroughModes() {
        assertEquals(VideoFit.ZOOM, VideoFit.FIT.next())
        assertEquals(VideoFit.STRETCH, VideoFit.ZOOM.next())
        assertEquals(VideoFit.FIT, VideoFit.STRETCH.next())
    }

    @Test
    fun subtitleStyleIsNormalized() {
        val style = SubtitleStyle(sizePercent = 900, bottomPercent = -5).normalized()
        assertEquals(SubtitleStyle.MAX_SIZE, style.sizePercent)
        assertEquals(0, style.bottomPercent)
    }

    @Test
    fun oldSettingsFileGetsDefaultSubtitleStyle() = runBlocking<Unit> {
        val file = Files.createTempFile("settings", ".json").toFile()
        file.writeText("""{"subtitleLanguage":"fr"}""")
        val loaded = FileSettingsStore(file).load()
        assertEquals(Settings(subtitleLanguage = "fr"), loaded)
        assertEquals(SubtitleStyle(), loaded.subtitleStyle)
    }
}
