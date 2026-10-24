package app.agregateur.core.playback

import kotlinx.serialization.Serializable

enum class SubtitleColor { WHITE, YELLOW, CYAN, GREEN, PINK }

enum class SubtitleBackground { NONE, TRANSLUCENT, OPAQUE }

/** Apparence des sous-titres, propre à l'appareil. Tailles et marges en pourcentage. */
@Serializable
data class SubtitleStyle(
    val sizePercent: Int = 100,
    val color: SubtitleColor = SubtitleColor.WHITE,
    val background: SubtitleBackground = SubtitleBackground.NONE,
    val outline: Boolean = true,
    val bottomPercent: Int = 8,
) {
    fun normalized() = copy(
        sizePercent = sizePercent.coerceIn(MIN_SIZE, MAX_SIZE),
        bottomPercent = bottomPercent.coerceIn(0, MAX_BOTTOM),
    )

    companion object {
        const val MIN_SIZE = 50
        const val MAX_SIZE = 250
        const val MAX_BOTTOM = 40
    }
}

/** Remplissage de l'écran : l'image entière, recadrée pour remplir, ou étirée. */
enum class VideoFit {
    FIT, ZOOM, STRETCH;

    fun next(): VideoFit = entries[(ordinal + 1) % entries.size]
}

object PlayerControls {
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    const val DOUBLE_TAP_SEEK_MS = 10_000L
    const val MAX_SUBTITLE_DELAY_MS = 60_000L

    /** Un glissement sur toute la largeur de l'écran avance ou recule de 90 s au plus. */
    const val FULL_SWIPE_SEEK_MS = 90_000L

    val SLEEP_MINUTES = listOf(0, 15, 30, 45, 60, 90)

    fun stepDelay(currentMs: Long, deltaMs: Long): Long =
        (currentMs + deltaMs).coerceIn(-MAX_SUBTITLE_DELAY_MS, MAX_SUBTITLE_DELAY_MS)

    fun seekBy(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val target = (positionMs + deltaMs).coerceAtLeast(0L)
        return if (durationMs > 0) target.coerceAtMost(durationMs) else target
    }

    /** [fraction] : déplacement horizontal divisé par la largeur de l'écran (négatif vers la gauche). */
    fun swipeSeekTarget(startMs: Long, fraction: Float, durationMs: Long): Long =
        seekBy(startMs, (fraction * FULL_SWIPE_SEEK_MS).toLong(), durationMs)

    /** [fraction] : déplacement vertical vers le haut divisé par la hauteur de l'écran. */
    fun adjustLevel(start: Float, fraction: Float): Float = (start + fraction).coerceIn(0f, 1f)
}
