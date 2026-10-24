package app.agregateur.core.playback

import app.agregateur.core.model.Stream

/** Ce que le nom d'une source dit de sa vidéo (les modules l'écrivent dans `name`, `title`, `description` ou le fichier). */
data class StreamTraits(val height: Int?, val hevc: Boolean, val tenBit: Boolean)

/** Ce que les décodeurs vidéo de l'appareil savent lire. Hauteur 0 : aucun décodeur pour ce format. */
data class DecoderCaps(val maxAvcHeight: Int, val maxHevcHeight: Int, val hevc10Bit: Boolean) {
    companion object {
        val UNKNOWN = DecoderCaps(maxAvcHeight = Int.MAX_VALUE, maxHevcHeight = Int.MAX_VALUE, hevc10Bit = true)
    }
}

private val heightPattern = Regex("""(?i)(?<![0-9])(4320|2160|1440|1080|720|576|480|360)[pi](?![a-z0-9])""")
private val uhdPattern = Regex("""(?i)(?<![a-z0-9])(4k|uhd)(?![a-z0-9])""")
private val hevcPattern = Regex("""(?i)(?<![a-z0-9])(hevc|x\.?265|h\.?265)(?![a-z0-9])""")
private val tenBitPattern = Regex("""(?i)(?<![a-z0-9])(10.?bits?|hdr10\+?|hdr|dv|dovi|dolby.?vision)(?![a-z0-9])""")

fun Stream.traits(): StreamTraits {
    val text = listOfNotNull(name, title, description, behaviorHints.filename).joinToString(" ")
    val height = heightPattern.findAll(text).map { it.groupValues[1].toInt() }.maxOrNull()
        ?: if (uhdPattern.containsMatchIn(text)) 2160 else null
    return StreamTraits(height, hevcPattern.containsMatchIn(text), tenBitPattern.containsMatchIn(text))
}

/** Faux seulement si le nom annonce un format que l'appareil ne sait pas décoder ; une source muette reste tentée. */
fun StreamTraits.fits(caps: DecoderCaps): Boolean {
    val h = height ?: return !(hevc && (caps.maxHevcHeight == 0 || (tenBit && !caps.hevc10Bit)))
    return if (hevc) {
        h <= caps.maxHevcHeight && (!tenBit || caps.hevc10Bit)
    } else {
        // Sans codec annoncé : H.264 le plus souvent ; un 10 bits sans HEVC (AV1, VP9) n'est pas départageable ici.
        h <= maxOf(caps.maxAvcHeight, caps.maxHevcHeight)
    }
}

/** Plus petit = préféré : 1080p d'abord (assez lourd pour être beau, assez léger pour un téléphone et un torrent). */
fun StreamTraits.qualityPenalty(): Int = when (val h = height) {
    null -> 400
    else -> kotlin.math.abs(h - PREFERRED_HEIGHT) + if (tenBit) 100 else 0
}

private const val PREFERRED_HEIGHT = 1080
