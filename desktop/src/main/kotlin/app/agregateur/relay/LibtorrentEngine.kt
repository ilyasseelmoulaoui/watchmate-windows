package app.agregateur.relay

import app.agregateur.core.playback.RelayTarget
import app.agregateur.relay.torrent.PieceStore
import app.agregateur.relay.torrent.TorrentEngine
import app.agregateur.relay.torrent.TorrentStatus
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.torrent_flags_t

/**
 * Moteur pair-à-pair (libtorrent4j). Les données vont dans un dossier de cache vidé à chaque démarrage ;
 * un torrent qui n'est plus lu depuis [idleMs] est retiré avec ses fichiers.
 */
class LibtorrentEngine(
    private val dir: File,
    private val metadataTimeoutMs: Long = 60_000,
    private val idleMs: Long = 10 * 60_000,
) : TorrentEngine, Closeable {
    /** Créé au `start()` : la DLL native doit être extraite avant le chargement de la classe JNI. */
    private lateinit var session: SessionManager
    private val active = ConcurrentHashMap<String, Active>()
    private val janitor = Executors.newSingleThreadScheduledExecutor { Thread(it, "relay-janitor").apply { isDaemon = true } }

    private class Active(val handle: TorrentHandle) {
        val stores = ConcurrentHashMap<Int, Store>()
        @Volatile var lastUse = System.currentTimeMillis()
    }

    fun start(): LibtorrentEngine {
        LibtorrentRuntime.ensureLoaded()
        dir.deleteRecursively()
        dir.mkdirs()
        val settings = SettingsPack()
            .activeDownloads(4)
            .connectionsLimit(200)
        session = SessionManager(false).also { it.start(SessionParams(settings)) }
        janitor.scheduleWithFixedDelay(::removeIdle, 1, 1, TimeUnit.MINUTES)
        return this
    }

    override fun close() {
        janitor.shutdownNow()
        active.values.forEach { it.stores.values.forEach(Store::close) }
        if (::session.isInitialized) session.stop()
        dir.deleteRecursively()
    }

    override fun open(target: RelayTarget.Torrent): PieceStore {
        val entry = synchronized(active) { active[target.infoHash] ?: add(target) }
        val info = awaitMetadata(entry, target.infoHash)
        val file = chooseFile(info, target.fileIdx)
        entry.lastUse = System.currentTimeMillis()
        return synchronized(entry) {
            entry.stores.getOrPut(file) {
                // Premier fichier choisi : tous les autres sont ignorés d'un coup. Le torrent ne passe jamais par
                // l'état « terminé » (rien de voulu), où libtorrent couperait les pairs complets.
                if (entry.stores.isEmpty()) {
                    entry.handle.prioritizeFiles(Array(info.numFiles()) { if (it == file) Priority.DEFAULT else Priority.IGNORE })
                } else {
                    entry.handle.filePriority(file, Priority.DEFAULT)
                }
                Store(entry, info, file)
            }
        }
    }

    override fun status(infoHash: String): TorrentStatus? {
        val entry = active[infoHash] ?: return null
        if (!entry.handle.isValid) return null
        val s = entry.handle.status()
        return TorrentStatus(s.state().name.lowercase(), s.numPeers(), s.downloadRate().toLong(), s.progress())
    }

    /**
     * Le torrent est ajouté par son lien magnet ; le fichier est choisi dès l'arrivée des métadonnées. Pas de
     * « ne rien télécharger par défaut » : un torrent sans fichier voulu est « terminé » et perd ses pairs complets.
     */
    private fun add(target: RelayTarget.Torrent): Active {
        val magnet = buildString {
            append("magnet:?xt=urn:btih:").append(target.infoHash)
            target.trackers.forEach { append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        session.download(magnet, dir, torrent_flags_t())
        val hash = Sha1Hash.parseHex(target.infoHash)
        val deadline = System.currentTimeMillis() + ADD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val handle = session.find(hash)
            if (handle != null && handle.isValid) return Active(handle).also { active[target.infoHash] = it }
            Thread.sleep(POLL_MS)
        }
        throw RelayException.timeout("Le moteur pair-à-pair n'a pas pu ajouter ce torrent")
    }

    private fun awaitMetadata(entry: Active, infoHash: String): TorrentInfo {
        val deadline = System.currentTimeMillis() + metadataTimeoutMs
        while (true) {
            if (!entry.handle.isValid) throw RelayException.upstream("Torrent retiré")
            if (entry.handle.status().hasMetadata()) entry.handle.torrentFile()?.let { return it }
            if (System.currentTimeMillis() >= deadline) {
                remove(infoHash)
                throw RelayException.timeout("Aucun pair n'a fourni la description de ce torrent")
            }
            Thread.sleep(POLL_MS)
        }
    }

    /** L'index du module s'il désigne un vrai fichier, sinon le plus gros (règle du protocole des modules). */
    private fun chooseFile(info: TorrentInfo, fileIdx: Int?): Int {
        val files = info.files()
        val real = (0 until files.numFiles()).filterNot(files::padFileAt)
        if (fileIdx != null) {
            if (fileIdx in real) return fileIdx
            throw RelayException.notFound("Fichier n°$fileIdx absent du torrent")
        }
        return real.maxByOrNull(files::fileSize) ?: throw RelayException.notFound("Torrent vide")
    }

    private fun removeIdle() {
        val now = System.currentTimeMillis()
        active.filterValues { now - it.lastUse > idleMs }.keys.forEach(::remove)
    }

    private fun remove(infoHash: String) {
        val entry = active.remove(infoHash) ?: return
        entry.stores.values.forEach(Store::close)
        if (entry.handle.isValid) session.remove(entry.handle, SessionHandle.DELETE_FILES)
    }

    private class Store(private val entry: Active, info: TorrentInfo, file: Int) : PieceStore, Closeable {
        private val handle = entry.handle
        private val fileOffset = info.files().fileOffset(file)
        private val path = File(handle.savePath(), info.files().filePath(file))
        private var raf: RandomAccessFile? = null

        override val fileName: String = info.files().fileName(file)
        override val fileSize: Long = info.files().fileSize(file)
        override val pieceLength: Int = info.pieceLength()

        override fun pieceAt(position: Long): Int = ((fileOffset + position) / pieceLength).toInt()

        override fun pieceEnd(piece: Int): Long = minOf(fileSize, (piece + 1L) * pieceLength - fileOffset)

        override fun havePiece(piece: Int): Boolean {
            if (!handle.isValid) throw IOException("Torrent retiré")
            return handle.havePiece(piece)
        }

        /** Échéances croissantes : la première pièce de chaque lecteur est la plus pressée. */
        override fun setUrgent(windows: List<List<Int>>) {
            if (!handle.isValid) return
            entry.lastUse = System.currentTimeMillis()
            handle.clearPieceDeadlines()
            windows.forEach { window ->
                window.forEachIndexed { i, piece -> handle.setPieceDeadline(piece, FIRST_DEADLINE_MS + i * DEADLINE_STEP_MS) }
            }
        }

        override fun awaitPiece(timeoutMs: Long) = Thread.sleep(minOf(timeoutMs, POLL_MS))

        @Synchronized
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            entry.lastUse = System.currentTimeMillis()
            val file = raf ?: RandomAccessFile(path, "r").also { raf = it }
            file.seek(position)
            return file.read(buffer, offset, length)
        }

        @Synchronized
        override fun close() {
            raf?.close()
            raf = null
        }
    }

    private companion object {
        const val POLL_MS = 200L
        const val ADD_TIMEOUT_MS = 10_000L
        const val FIRST_DEADLINE_MS = 0
        const val DEADLINE_STEP_MS = 200
    }
}
