package app.agregateur.relay

import app.agregateur.core.playback.RelayRequest
import app.agregateur.core.playback.RelayTarget
import app.agregateur.core.playback.RelayUrls
import app.agregateur.relay.archive.ArchiveMediaSource
import app.agregateur.relay.archive.BlockCache
import app.agregateur.relay.archive.RemoteVolumes
import app.agregateur.relay.archive.parserFor
import app.agregateur.relay.archive.selectEntry
import app.agregateur.relay.http.HttpRequest
import app.agregateur.relay.http.HttpResponder
import app.agregateur.relay.http.LocalHttpServer
import app.agregateur.relay.http.RangeSpec
import app.agregateur.relay.http.parseRange
import app.agregateur.relay.torrent.PieceStore
import app.agregateur.relay.torrent.TorrentEngine
import app.agregateur.relay.torrent.TorrentMediaSource
import java.io.Closeable
import java.security.SecureRandom
import java.util.WeakHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Le relais local : prépare torrents et archives et les sert au lecteur en HTTP, sur l'appareil seulement.
 * Chaque démarrage tire un secret ; une adresse sans ce secret est refusée.
 */
class Relay(
    private val torrents: TorrentEngine?,
    private val http: OkHttpClient,
    val token: String = newToken(),
) : Closeable {
    private val server = LocalHttpServer(::handle)
    private val archiveCache = object : LinkedHashMap<RelayTarget.Archive, ArchiveMediaSource>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RelayTarget.Archive, ArchiveMediaSource>?) = size > 16
    }
    private val torrentSources = WeakHashMap<PieceStore, TorrentMediaSource>()

    val port: Int get() = server.port

    fun start(): Relay = apply { server.start() }

    override fun close() = server.close()

    private fun handle(request: HttpRequest, responder: HttpResponder) {
        val url = "http://${RelayUrls.HOST}${request.target}".toHttpUrlOrNull()
        val parsed = url?.let { RelayUrls.parse(it, token) }
        try {
            when (parsed) {
                null -> responder.text(404, "Adresse inconnue")
                is RelayRequest.TorrentStatus -> {
                    val status = torrents?.status(parsed.infoHash) ?: return responder.text(404, "Torrent inactif")
                    val body = Json.encodeToString(status).toByteArray()
                    responder.send(200, mapOf("Content-Type" to "application/json", "Content-Length" to body.size.toString())) {
                        if (request.method == "GET") it.write(body)
                    }
                }
                is RelayRequest.Play -> serve(request, responder, source(parsed.target))
            }
        } catch (e: RelayException) {
            if (!responder.committed) responder.text(e.status, e.message ?: "")
        }
    }

    private fun source(target: RelayTarget): MediaSource = when (target) {
        is RelayTarget.Torrent -> {
            val engine = torrents ?: throw RelayException.unsupported("Moteur pair-à-pair absent")
            val store = engine.open(target)
            synchronized(torrentSources) { torrentSources.getOrPut(store) { TorrentMediaSource(store) } }
        }
        is RelayTarget.Archive -> synchronized(archiveCache) { archiveCache[target] } ?: run {
            val volumes = BlockCache(RemoteVolumes(http, target.urls))
            val parser = parserFor(target.kind)
            val entry = parser.locate(volumes, selectEntry(parser.entries(volumes), target.fileIdx, target.fileMustInclude))
            ArchiveMediaSource(volumes, entry).also { synchronized(archiveCache) { archiveCache[target] = it } }
        }
    }

    private fun serve(request: HttpRequest, responder: HttpResponder, source: MediaSource) {
        val size = source.size
        val headers = mutableMapOf(
            "Content-Type" to contentTypeOf(source.name),
            "Accept-Ranges" to "bytes",
            "Content-Disposition" to "inline; filename*=UTF-8''" + java.net.URLEncoder.encode(source.name, "UTF-8").replace("+", "%20"),
        )
        val (status, start, length) = when (val range = parseRange(request.header("Range"), size)) {
            RangeSpec.Full -> Triple(200, 0L, size)
            is RangeSpec.Partial -> {
                headers["Content-Range"] = "bytes ${range.start}-${range.endInclusive}/$size"
                Triple(206, range.start, range.length)
            }
            RangeSpec.Unsatisfiable -> {
                responder.send(416, mapOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
                return
            }
        }
        headers["Content-Length"] = length.toString()
        if (request.method == "HEAD") {
            responder.send(status, headers)
            return
        }
        source.open(start).use { input ->
            // Les en-têtes ne partent qu'avec le premier octet : une erreur précoce (archive, torrent) garde son statut.
            val buffer = ByteArray(BUFFER)
            val first = input.read(buffer, 0, minOf(BUFFER.toLong(), length).toInt())
            responder.send(status, headers) { out ->
                var remaining = length
                var n = first
                while (remaining > 0 && n > 0) {
                    out.write(buffer, 0, n)
                    remaining -= n
                    if (remaining > 0) n = input.read(buffer, 0, minOf(BUFFER.toLong(), remaining).toInt())
                }
            }
        }
    }

    companion object {
        private const val BUFFER = 64 * 1024

        fun newToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
