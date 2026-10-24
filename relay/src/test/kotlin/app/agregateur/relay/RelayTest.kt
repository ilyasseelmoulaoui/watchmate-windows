package app.agregateur.relay

import app.agregateur.core.playback.ArchiveKind
import app.agregateur.core.playback.RelayTarget
import app.agregateur.core.playback.RelayUrls
import app.agregateur.relay.torrent.PieceStore
import app.agregateur.relay.torrent.TorrentEngine
import app.agregateur.relay.torrent.TorrentStatus
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

class RelayTest {
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val video = fixture("video.bin")
    private val origin = MockWebServer().apply { dispatcher = RangeDispatcher(); start() }
    private val torrent = FakeStore(video, pieceLength = 16_384)
    private val relay = Relay(FakeEngine(torrent), client).start()

    @AfterTest
    fun close() {
        relay.close()
        origin.shutdown()
    }

    @Test
    fun `archive rar multi volumes servie avec plages`() {
        val urls = (1..4).map { originUrl("rar5.part$it.rar") }
        val url = RelayUrls.play(relay.port, relay.token, RelayTarget.Archive(ArchiveKind.RAR, urls, null, null))

        get(url).use {
            assertEquals(200, it.code)
            assertEquals("video/x-matroska", it.header("Content-Type"))
            assertEquals("bytes", it.header("Accept-Ranges"))
            assertContentEquals(video, it.body!!.bytes())
        }
        get(url, "bytes=40000-90000").use {
            assertEquals(206, it.code)
            assertEquals("bytes 40000-90000/${video.size}", it.header("Content-Range"))
            assertContentEquals(video.copyOfRange(40_000, 90_001), it.body!!.bytes())
        }
        get(url, "bytes=999999-").use { assertEquals(416, it.code) }
    }

    @Test
    fun `secret faux ou adresse inconnue refuses`() {
        val target = RelayTarget.Archive(ArchiveKind.ZIP, listOf(originUrl("stocke.zip")), null, null)
        get(RelayUrls.play(relay.port, "0".repeat(48), target)).use { assertEquals(404, it.code) }
        get("http://127.0.0.1:${relay.port}/${relay.token}/autre").use { assertEquals(404, it.code) }
        assertEquals(0, origin.requestCount)
    }

    @Test
    fun `archive compressee refusee avant tout envoi de donnees`() {
        val target = RelayTarget.Archive(ArchiveKind.ZIP, listOf(originUrl("compresse.zip")), null, null)
        get(RelayUrls.play(relay.port, relay.token, target)).use {
            assertEquals(415, it.code)
            assertTrue(it.body!!.string().contains("compressé"))
        }
    }

    @Test
    fun `torrent lu au fil de l arrivee des pieces, en priorite devant le lecteur`() {
        val target = RelayTarget.Torrent("0".repeat(40), null, emptyList())
        val url = RelayUrls.play(relay.port, relay.token, target)
        val feeder = Thread {
            // Les pièces arrivent dans l'ordre demandé en urgence, comme le ferait le moteur.
            while (!torrent.complete()) {
                torrent.deliverNextUrgent()
                Thread.sleep(2)
            }
        }.apply { isDaemon = true; start() }

        get(url, "bytes=100000-").use {
            assertEquals(206, it.code)
            assertContentEquals(video.copyOfRange(100_000, video.size), it.body!!.bytes())
        }
        assertEquals(100_000 / 16_384, torrent.firstUrgent, "la première pièce demandée est celle du lecteur")
        get(url).use { assertContentEquals(video, it.body!!.bytes()) }
        feeder.join(5_000)

        get(RelayUrls.torrentStatus(relay.port, relay.token, "0".repeat(40))).use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.string().contains("\"peers\":3"))
        }
    }

    /** 127.0.0.1 plutôt que le nom de la machine : la règle des adresses n'accepte le http qu'en local. */
    private fun originUrl(name: String) = "http://127.0.0.1:${origin.port}/$name"

    private fun get(url: String, range: String? = null) =
        client.newCall(Request.Builder().url(url).apply { range?.let { header("Range", it) } }.build()).execute()

    private fun fixture(name: String): ByteArray = File(javaClass.getResource("/archives/$name")!!.toURI()).readBytes()

    /** Serveur de fichiers qui respecte `Range`, comme un hébergeur réel. */
    private inner class RangeDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val name = request.requestUrl!!.pathSegments.last()
            val file = javaClass.getResource("/archives/$name") ?: return MockResponse().setResponseCode(404)
            val bytes = File(file.toURI()).readBytes()
            val range = Regex("bytes=(\\d+)-(\\d*)").find(request.getHeader("Range") ?: "")
                ?: return MockResponse().setBody(Buffer().write(bytes))
            val start = range.groupValues[1].toInt()
            val end = range.groupValues[2].toIntOrNull()?.coerceAtMost(bytes.size - 1) ?: (bytes.size - 1)
            return MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                .setBody(Buffer().write(bytes, start, end - start + 1))
        }
    }
}

private class FakeEngine(private val store: PieceStore) : TorrentEngine {
    override fun open(target: RelayTarget.Torrent): PieceStore = store
    override fun status(infoHash: String) = TorrentStatus("downloading", peers = 3, downloadRate = 1000, progress = 0.5f)
}

/** Torrent d'un seul fichier dont les pièces n'arrivent que lorsqu'on les livre. */
private class FakeStore(private val data: ByteArray, override val pieceLength: Int) : PieceStore {
    private val lock = Object()
    private val pieces = (data.size + pieceLength - 1) / pieceLength
    private val have = BooleanArray(pieces)
    private var urgent: List<List<Int>> = emptyList()
    @Volatile var firstUrgent = -1

    override val fileName = "film.mkv"
    override val fileSize = data.size.toLong()
    override fun pieceAt(position: Long) = (position / pieceLength).toInt()
    override fun pieceEnd(piece: Int) = minOf(fileSize, (piece + 1L) * pieceLength)
    override fun havePiece(piece: Int) = synchronized(lock) { have[piece] }

    override fun setUrgent(windows: List<List<Int>>) = synchronized(lock) {
        urgent = windows
        if (firstUrgent < 0) windows.firstOrNull()?.firstOrNull()?.let { firstUrgent = it }
    }

    override fun awaitPiece(timeoutMs: Long) = synchronized(lock) { lock.wait(timeoutMs) }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        System.arraycopy(data, position.toInt(), buffer, offset, length)
        return length
    }

    fun complete() = synchronized(lock) { have.all { it } }

    fun deliverNextUrgent() = synchronized(lock) {
        val next = urgent.flatten().firstOrNull { !have[it] } ?: return
        have[next] = true
        lock.notifyAll()
    }
}
