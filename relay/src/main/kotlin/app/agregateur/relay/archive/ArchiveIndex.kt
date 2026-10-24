package app.agregateur.relay.archive

import app.agregateur.core.playback.ArchiveKind
import app.agregateur.core.playback.fileMatcher
import app.agregateur.relay.MediaSource
import app.agregateur.relay.RelayException
import java.io.IOException
import java.io.InputStream

/** Morceau d'un fichier stocké tel quel dans un volume. */
data class Segment(val volume: Int, val offset: Long, val length: Long)

/** Un fichier d'archive. `stored` : présent sans compression ni chiffrement, donc lisible par plages. */
data class ArchiveEntry(val name: String, val size: Long, val stored: Boolean, val segments: List<Segment>)

interface ArchiveParser {
    /** Fichiers de l'archive, dossiers exclus, dans l'ordre où elle les range. */
    fun entries(volumes: Volumes): List<ArchiveEntry>

    /** Position exacte des données du fichier choisi, quand l'index seul ne la donne pas (ZIP). */
    fun locate(volumes: Volumes, entry: ArchiveEntry): ArchiveEntry = entry
}

fun parserFor(kind: ArchiveKind): ArchiveParser = when (kind) {
    ArchiveKind.RAR -> RarParser
    ArchiveKind.ZIP -> ZipParser
    ArchiveKind.TAR -> TarParser
}

/**
 * Choix du fichier : l'index donné par le module, sinon le plus gros fichier qui correspond au motif,
 * sinon le plus gros fichier (même règle que pour un torrent).
 */
fun selectEntry(entries: List<ArchiveEntry>, fileIdx: Int?, pattern: String?): ArchiveEntry {
    val chosen = when {
        fileIdx != null -> entries.getOrNull(fileIdx) ?: throw RelayException.notFound("Fichier n°$fileIdx absent de l'archive")
        pattern != null -> {
            val regex = fileMatcher(pattern) ?: throw RelayException.notFound("Motif de fichier invalide")
            entries.filter { regex.containsMatchIn(it.name) }.maxByOrNull { it.size }
                ?: throw RelayException.notFound("Aucun fichier de l'archive ne correspond")
        }
        else -> entries.maxByOrNull { it.size } ?: throw RelayException.notFound("Archive vide")
    }
    if (!chosen.stored) throw RelayException.unsupported("« ${chosen.name} » est compressé ou chiffré dans l'archive")
    return chosen
}

/** Un fichier d'archive vu comme une vidéo : ses morceaux, mis bout à bout. */
class ArchiveMediaSource(private val volumes: Volumes, private val entry: ArchiveEntry) : MediaSource {
    override val name: String get() = entry.name.substringAfterLast('/')
    override val size: Long get() = entry.size

    override fun open(position: Long): InputStream = SegmentsStream(position)

    private inner class SegmentsStream(private var position: Long) : InputStream() {
        private var current: InputStream? = null
        private var remainingInCurrent = 0L

        private fun ensure(): Boolean {
            if (remainingInCurrent > 0) return true
            current?.close()
            current = null
            if (position >= entry.size) return false
            var start = 0L
            for (segment in entry.segments) {
                if (position < start + segment.length) {
                    val inside = position - start
                    current = volumes.stream(segment.volume, segment.offset + inside, segment.offset + segment.length)
                    remainingInCurrent = segment.length - inside
                    return true
                }
                start += segment.length
            }
            return false
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!ensure()) return -1
            val n = current!!.read(b, off, minOf(len.toLong(), remainingInCurrent).toInt())
            if (n < 0) throw IOException("Volume interrompu avant la fin attendue")
            remainingInCurrent -= n
            position += n
            return n
        }

        override fun close() {
            current?.close()
        }
    }
}
