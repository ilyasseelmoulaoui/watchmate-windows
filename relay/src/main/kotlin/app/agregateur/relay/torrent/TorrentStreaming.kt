package app.agregateur.relay.torrent

import app.agregateur.core.playback.RelayTarget
import app.agregateur.relay.MediaSource
import app.agregateur.relay.RelayException
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.Serializable

/** Accès à un fichier d'un torrent ; mis en œuvre par le moteur pair-à-pair de l'application. */
interface PieceStore {
    val fileName: String
    val fileSize: Long
    val pieceLength: Int

    /** Pièce qui contient l'octet [position] du fichier. */
    fun pieceAt(position: Long): Int

    /** Position dans le fichier juste après la pièce [piece] (bornée à la taille du fichier). */
    fun pieceEnd(piece: Int): Long

    fun havePiece(piece: Int): Boolean

    /** Pièces à obtenir en urgence, la plus pressée d'abord, une liste par lecteur. Remplace les demandes précédentes. */
    fun setUrgent(windows: List<List<Int>>)

    /** Attend qu'une pièce arrive, au plus [timeoutMs]. */
    fun awaitPiece(timeoutMs: Long)

    /** Lit des octets déjà présents sur le disque. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

@Serializable
data class TorrentStatus(
    val state: String,
    val peers: Int,
    val downloadRate: Long,
    val progress: Float,
)

/** Le moteur pair-à-pair, côté relais : il ne sait que fournir un fichier et un état. */
interface TorrentEngine {
    /** Ajoute le torrent si besoin, attend ses métadonnées et renvoie le fichier choisi. */
    fun open(target: RelayTarget.Torrent): PieceStore

    fun status(infoHash: String): TorrentStatus?
}

/**
 * Un fichier de torrent lu comme une vidéo. Chaque lecteur ouvert (le lecteur vidéo en ouvre parfois plusieurs, au début
 * et à la fin du fichier) demande en urgence les pièces devant sa position ; le reste du fichier arrive ensuite.
 */
class TorrentMediaSource(
    private val store: PieceStore,
    private val windowBytes: Long = 16L * 1024 * 1024,
    private val readTimeoutMs: Long = 120_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : MediaSource {
    override val name: String get() = store.fileName
    override val size: Long get() = store.fileSize

    private val readers = mutableSetOf<Reader>()

    override fun open(position: Long): InputStream = Reader(position).also {
        synchronized(readers) { readers += it }
        refresh()
    }

    private fun windowFrom(position: Long): List<Int> {
        if (position >= store.fileSize) return emptyList()
        val first = store.pieceAt(position)
        val last = store.pieceAt(store.fileSize - 1)
        val count = maxOf(MIN_WINDOW, ((windowBytes + store.pieceLength - 1) / store.pieceLength).toInt())
        return (first..minOf(last, first + count - 1)).filterNot(store::havePiece)
    }

    private fun refresh() {
        val windows = synchronized(readers) { readers.map { windowFrom(it.position) } }
        store.setUrgent(windows.filter { it.isNotEmpty() })
    }

    private inner class Reader(@Volatile var position: Long) : InputStream() {
        private var piece = -1

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (position >= store.fileSize) return -1
            val current = store.pieceAt(position)
            if (current != piece) {
                piece = current
                refresh()
            }
            val deadline = clock() + readTimeoutMs
            while (!store.havePiece(current)) {
                if (clock() >= deadline) throw RelayException.timeout("Aucun pair n'a fourni la suite de la vidéo à temps")
                store.awaitPiece(WAIT_STEP_MS)
            }
            val available = minOf(len.toLong(), store.pieceEnd(current) - position).toInt()
            val n = store.read(position, b, off, available)
            if (n <= 0) throw IOException("Lecture impossible dans le fichier du torrent")
            position += n
            return n
        }

        override fun close() {
            synchronized(readers) { readers -= this }
            refresh()
        }
    }

    private companion object {
        const val MIN_WINDOW = 4
        const val WAIT_STEP_MS = 500L
    }
}
