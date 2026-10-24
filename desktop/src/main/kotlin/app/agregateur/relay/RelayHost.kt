package app.agregateur.relay

import app.agregateur.core.addon.LocalProxySelector
import app.agregateur.core.playback.RelayTarget
import app.agregateur.core.playback.RelayUrls
import app.agregateur.relay.torrent.TorrentStatus
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

data class RelayEndpoint(val port: Int, val token: String, val torrents: Boolean) {
    fun url(target: RelayTarget): String = RelayUrls.play(port, token, target)
}

/**
 * Relais local dans le processus de l'application (sur Android, c'est un service à part). Chaque lecture tient un
 * bail ; sans bail pendant [idleStopMs], le relais et le moteur pair-à-pair s'arrêtent.
 */
class RelayHost(private val cacheDir: File, private val idleStopMs: Long = 60_000) {
    private val lock = Any()
    private var relay: Relay? = null
    private var engine: LibtorrentEngine? = null
    private var leases = 0
    private var stopLater: ScheduledFuture<*>? = null
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "relay-host").apply { isDaemon = true } }

    inner class Lease internal constructor() {
        private var released = false

        fun release() = synchronized(lock) {
            if (released) return
            released = true
            leases--
            if (leases == 0) stopLater = timer.schedule({ synchronized(lock) { if (leases == 0) stop() } }, idleStopMs, TimeUnit.MILLISECONDS)
        }
    }

    fun acquire(): Lease = synchronized(lock) {
        leases++
        stopLater?.cancel(false)
        stopLater = null
        Lease()
    }

    /** Démarre le relais au premier appel (le moteur libtorrent prend une à deux secondes) ; l'appelant tient un bail. */
    suspend fun endpoint(): RelayEndpoint = withTimeout(CONNECT_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val current = relay ?: start()
                RelayEndpoint(current.port, current.token, engine != null)
            }
        }
    }

    /** Pairs, débit et avancement d'un torrent en cours de lecture (même processus : pas besoin de passer par HTTP). */
    fun torrentStatus(infoHash: String): TorrentStatus? = synchronized(lock) { engine }?.let { runCatching { it.status(infoHash) }.getOrNull() }

    private fun start(): Relay {
        // Sans moteur pair-à-pair (bibliothèque native absente), les archives restent lisibles.
        engine = runCatching {
            LibtorrentRuntime.ensureLoaded()
            LibtorrentEngine(File(cacheDir, "relay/torrents")).start()
        }
            .onFailure { System.err.println("Moteur pair-à-pair indisponible : $it") }
            .getOrNull()
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .proxySelector(LocalProxySelector)
            .build()
        return Relay(engine, http).start().also { relay = it }
    }

    private fun stop() {
        relay?.close()
        engine?.close()
        relay = null
        engine = null
    }

    fun close() = synchronized(lock) {
        stop()
        timer.shutdownNow()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
