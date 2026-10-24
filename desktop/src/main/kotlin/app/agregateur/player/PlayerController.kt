package app.agregateur.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.agregateur.DesktopApp
import app.agregateur.core.addon.LocalProxySelector
import app.agregateur.core.library.TitleSnapshot
import app.agregateur.core.model.Stream
import app.agregateur.core.model.Subtitle
import app.agregateur.core.party.PartyMedia
import app.agregateur.core.party.PartyPlayer
import app.agregateur.core.playback.RelayTarget
import app.agregateur.core.playback.languageName
import app.agregateur.core.playback.normalizeLanguage
import app.agregateur.core.playback.pickSubtitles
import app.agregateur.core.playback.subtitleFormatOf
import app.agregateur.relay.torrent.TorrentStatus
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters

/**
 * Une lecture demandée : l'adresse et les en-têtes viennent déjà de `route()`. Pour une source qui passe par le relais,
 * `relayTarget` est fixé et l'adresse locale est obtenue au moment de lire (le relais peut avoir redémarré).
 */
data class PlayRequest(
    val url: String,
    val headers: Map<String, String>,
    val type: String,
    val videoId: String,
    val stream: Stream,
    val title: String,
    val titleId: String,
    val poster: String?,
    val moduleUrl: String,
    val startPositionMs: Long = 0L,
    val relayTarget: RelayTarget? = null,
    /** Distingue deux lectures du même titre : chaque lecture a son propre lecteur. */
    val launchId: Long = System.nanoTime(),
) {
    val partyMedia: PartyMedia get() = PartyMedia(type, titleId, videoId, title, poster, moduleUrl)
}

sealed interface PlayerStatus {
    data object Preparing : PlayerStatus
    data object Ready : PlayerStatus
    /** `detail` : message du relais quand c'est lui qui refuse (archive compressée, aucun pair…). */
    data class Failed(val code: String, val detail: String? = null) : PlayerStatus
}

/** Piste proposée à l'utilisateur. `external` : sous-titre d'un module, dessiné par l'application. */
data class TrackChoice(val key: String, val label: String, val detail: String?, val external: Boolean = false)

/**
 * Lecteur d'une lecture (équivalent du PlayerViewModel Android) : VLC décode, Compose dessine. Tout l'état est lu et
 * modifié sur le fil de l'interface ; les rappels de VLC y sont renvoyés.
 */
@Stable
class PlayerController(private val app: DesktopApp, val request: PlayRequest) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val snapshot = TitleSnapshot(request.type, request.titleId, request.title, request.poster, request.moduleUrl)

    val frames = VideoFrames()
    private var factory: MediaPlayerFactory? = null
    private var player: EmbeddedMediaPlayer? = null
    private val relayLease = request.relayTarget?.let { app.relay.acquire() }

    var status by mutableStateOf<PlayerStatus>(PlayerStatus.Preparing)
        private set
    var torrent by mutableStateOf<TorrentStatus?>(null)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var playing by mutableStateOf(false)
        private set
    /** Ce que l'utilisateur (ou la salle) a demandé : lecture ou pause, même pendant un chargement. */
    var wantsPlay by mutableStateOf(true)
        private set
    var buffering by mutableStateOf(true)
        private set
    var ended by mutableStateOf(false)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var volume by mutableIntStateOf(100)
        private set
    var muted by mutableStateOf(false)
        private set
    var subtitleDelayMs by mutableLongStateOf(0L)
        private set
    var audioTracks by mutableStateOf<List<TrackChoice>>(emptyList())
        private set
    var selectedAudio by mutableStateOf<String?>(null)
        private set
    var subtitleTracks by mutableStateOf<List<TrackChoice>>(emptyList())
        private set
    var selectedSubtitle by mutableStateOf<String?>(null)
        private set
    /** Texte du sous-titre de module à afficher maintenant (null : rien, ou piste intégrée dessinée par VLC). */
    var cueText by mutableStateOf<String?>(null)
        private set

    private var started = false
    private var released = false
    private var autoRetries = 0
    private var subtitles: List<Subtitle> = emptyList()
    private val loadedCues = mutableMapOf<String, CueTrack>()
    private var activeCues: CueTrack? = null
    private var cueJob: Job? = null
    private var currentUrl: String? = null

    /** Ce que la salle « regarder ensemble » pilote : le lecteur local. */
    private val partyPlayer = object : PartyPlayer {
        override val positionMs: Long get() = player?.status()?.time()?.coerceAtLeast(0L) ?: 0L
        override val playWhenReady: Boolean get() = wantsPlay
        override fun setPlayWhenReady(play: Boolean) = this@PlayerController.setPlayWhenReady(play)
        override fun seekTo(positionMs: Long) = this@PlayerController.seekTo(positionMs)
        override fun setSpeed(speed: Float) {
            player?.controls()?.setRate(speed)
            this@PlayerController.speed = speed
        }
    }

    private val events = object : MediaPlayerEventAdapter() {
        override fun playing(mediaPlayer: MediaPlayer) = post {
            started = true
            ended = false
            updateBuffering(false)
            refreshTracks()
        }
        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = post { updateBuffering(newCache < 100f) }
        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = post { durationMs = newLength.coerceAtLeast(0L) }
        override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: TrackType, id: Int) = post { refreshTracks() }
        override fun elementaryStreamDeleted(mediaPlayer: MediaPlayer, type: TrackType, id: Int) = post { refreshTracks() }
        override fun elementaryStreamSelected(mediaPlayer: MediaPlayer, type: TrackType, id: Int) = post { refreshTracks() }
        override fun finished(mediaPlayer: MediaPlayer) = post {
            ended = true
            wantsPlay = false
            positionMs = durationMs
            saveProgress(atEnd = true)
            app.party.onLocalPlayWhenReady(false)
        }
        override fun error(mediaPlayer: MediaPlayer) = post { onError() }
    }

    fun start() {
        if (!VlcRuntime.available) {
            status = PlayerStatus.Failed(VLC_MISSING)
            return
        }
        val style = app.settings.settings.value.subtitleStyle
        val f = VlcRuntime.newFactory(style).also { factory = it }
        player = f.mediaPlayers().newEmbeddedMediaPlayer().also { p ->
            p.videoSurface().set(CallbackVideoSurface(frames, frames, true, VideoSurfaceAdapters.getVideoSurfaceAdapter()))
            p.events().addMediaPlayerEventListener(events)
            p.audio().setVolume(volume)
        }
        scope.launch {
            subtitles = pickSubtitles(
                withContext(Dispatchers.IO) {
                    app.titles.subtitles(app.modules.modules.value, request.type, request.videoId, request.stream)
                },
                app.settings.settings.value.subtitleLanguage,
            )
            if (released) return@launch
            load(request.startPositionMs)
        }
        (request.relayTarget as? RelayTarget.Torrent)?.let { target -> scope.launch { followTorrent(target) } }
        scope.launch { pollPosition() }
        scope.launch {
            while (!released) {
                delay(PROGRESS_INTERVAL_MS)
                if (playing) saveProgress()
            }
        }
    }

    /** Prépare la lecture ; pour le relais, l'adresse est redemandée (nouveau secret s'il a redémarré). */
    private suspend fun load(positionMs: Long) {
        val p = player ?: return
        status = PlayerStatus.Preparing
        updateBuffering(true)
        val url = request.relayTarget?.let { target ->
            val endpoint = try {
                app.relay.endpoint()
            } catch (_: TimeoutCancellationException) {
                status = PlayerStatus.Failed(RELAY_UNAVAILABLE)
                return
            }
            if (target is RelayTarget.Torrent && !endpoint.torrents) {
                status = PlayerStatus.Failed(P2P_UNAVAILABLE)
                return
            }
            endpoint.url(target)
        } ?: request.url
        if (released) return
        currentUrl = url
        val options = buildList {
            add(":start-time=${positionMs / 1000.0}")
            add(":network-caching=${if (request.relayTarget != null) 4000 else 1500}")
            add(":http-reconnect")
            request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { add(":http-user-agent=${it.value}") }
            request.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { add(":http-referrer=${it.value}") }
        }
        // Attach avant le démarrage : sinon la mise en mémoire tampon arrive hors salle et l'attente collective
        // n'est jamais armée pour le chargement initial.
        app.party.attach(partyPlayer, request.partyMedia)
        val ok = withContext(Dispatchers.IO) { p.media().play(url, *options.toTypedArray()) }
        if (!ok) {
            status = PlayerStatus.Failed(OPEN_FAILED)
            return
        }
        p.controls().setRate(speed)
        if (!wantsPlay) p.controls().setPause(true)
        status = PlayerStatus.Ready
    }

    private suspend fun pollPosition() {
        while (!released) {
            player?.let { p ->
                val time = p.status().time()
                if (time >= 0 && !ended) positionMs = time
                playing = p.status().isPlaying
                val length = p.status().length()
                if (length > 0) durationMs = length
            }
            activeCues?.let { cueText = it.at(positionMs - subtitleDelayMs) }
            delay(POLL_MS)
        }
    }

    /** Pairs, débit et avancement tant que la vidéo n'est pas en train de jouer. */
    private suspend fun followTorrent(target: RelayTarget.Torrent) {
        while (!released) {
            torrent = if (!playing) withContext(Dispatchers.IO) { app.relay.torrentStatus(target.infoHash) } else null
            delay(TORRENT_POLL_MS)
        }
    }

    private fun updateBuffering(value: Boolean) {
        val effective = value && wantsPlay
        if (buffering == effective) return
        buffering = effective
        app.party.onLocalBuffering(effective)
    }

    private fun onError() {
        if (released) return
        // Torrent pas encore prêt, relais qui redémarre : retenter avant d'abandonner.
        if (autoRetries < MAX_AUTO_RETRIES && request.relayTarget != null) {
            autoRetries++
            val position = if (autoRetries >= MAX_AUTO_RETRIES) 0L else positionMs.takeIf { it > 0 } ?: request.startPositionMs
            status = PlayerStatus.Preparing
            scope.launch {
                delay(AUTO_RETRY_DELAY_MS)
                if (!released) load(position)
            }
            return
        }
        val url = currentUrl
        status = PlayerStatus.Failed(PLAYBACK_ERROR)
        if (request.relayTarget != null && url != null) {
            scope.launch {
                val detail = withContext(Dispatchers.IO) { relayRefusal(url) }
                if (detail != null && status is PlayerStatus.Failed) status = PlayerStatus.Failed(PLAYBACK_ERROR, detail)
            }
        }
    }

    /** Le relais explique en texte brut pourquoi il refuse (archive compressée, aucun pair…). */
    private fun relayRefusal(url: String): String? = runCatching {
        localHttp.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { response ->
            val plain = response.header("Content-Type")?.startsWith("text/plain") == true
            if (!response.isSuccessful && plain) response.body?.string()?.take(300) else null
        }
    }.getOrNull()

    // ---- Commandes ----

    fun setPlayWhenReady(play: Boolean) {
        val p = player ?: return
        if (play && ended) {
            ended = false
            seekTo(0L)
            p.controls().play()
        }
        if (wantsPlay == play) return
        wantsPlay = play
        p.controls().setPause(!play)
        if (!play) {
            saveProgress()
            updateBuffering(false)
        }
        app.party.onLocalPlayWhenReady(play)
    }

    fun togglePlay() = setPlayWhenReady(!wantsPlay || ended)

    fun seekTo(target: Long) {
        val p = player ?: return
        val clamped = if (durationMs > 0) target.coerceIn(0L, durationMs) else target.coerceAtLeast(0L)
        p.controls().setTime(clamped)
        positionMs = clamped
        ended = false
        app.party.onLocalSeek(clamped)
    }

    fun setPlaybackSpeed(value: Float) {
        player?.controls()?.setRate(value)
        speed = value
    }

    fun changeVolume(value: Int) {
        val v = value.coerceIn(0, MAX_VOLUME)
        player?.audio()?.setVolume(v)
        volume = v
        if (muted && v > 0) setMute(false)
    }

    fun setMute(value: Boolean) {
        player?.audio()?.setMute(value)
        muted = value
    }

    fun setSubtitleDelay(ms: Long) {
        subtitleDelayMs = ms
        player?.subpictures()?.setDelay(ms * 1000L)
    }

    fun selectAudio(key: String) {
        val id = key.removePrefix("a:").toIntOrNull() ?: return
        player?.audio()?.setTrack(id)
        selectedAudio = key
    }

    fun disableSubtitles() {
        player?.subpictures()?.setTrack(-1)
        activeCues = null
        cueText = null
        cueJob?.cancel()
        selectedSubtitle = null
    }

    fun selectSubtitle(key: String) {
        cueJob?.cancel()
        if (key.startsWith("x:")) {
            val subtitle = subtitles.getOrNull(key.removePrefix("x:").toIntOrNull() ?: return) ?: return
            player?.subpictures()?.setTrack(-1)
            selectedSubtitle = key
            activeCues = loadedCues[key]
            if (activeCues == null) {
                cueJob = scope.launch {
                    val track = withContext(Dispatchers.IO) { download(subtitle) }
                    if (track != null) loadedCues[key] = track
                    if (selectedSubtitle == key) activeCues = track
                }
            }
        } else {
            val id = key.removePrefix("s:").toIntOrNull() ?: return
            activeCues = null
            cueText = null
            player?.subpictures()?.setTrack(id)
            selectedSubtitle = key
        }
    }

    private fun download(subtitle: Subtitle): CueTrack? = runCatching {
        subtitleHttp.newCall(Request.Builder().url(subtitle.url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            SubtitleParser.parse(response.body!!.bytes(), subtitleFormatOf(subtitle.url)).takeUnless { it.isEmpty }
        }
    }.getOrNull()

    private var subtitlesChosen = false

    private fun refreshTracks() {
        val p = player ?: return
        audioTracks = p.audio().trackDescriptions().filter { it.id() >= 0 }.map { TrackChoice("a:${it.id()}", cleanLabel(it.description()), null) }
        selectedAudio = p.audio().track().takeIf { it >= 0 }?.let { "a:$it" }
        val embedded = p.subpictures().trackDescriptions().filter { it.id() >= 0 }
            .map { TrackChoice("s:${it.id()}", cleanLabel(it.description()), tr("intégré", "embedded")) }
        val external = subtitles.mapIndexed { index, s ->
            TrackChoice("x:$index", s.label ?: languageName(s.lang), moduleLabel(), external = true)
        }
        subtitleTracks = embedded + external
        val vlcSub = p.subpictures().track()
        if (!selectedSubtitle.orEmpty().startsWith("x:")) selectedSubtitle = vlcSub.takeIf { it >= 0 }?.let { "s:$it" }
        if (!subtitlesChosen && started) {
            subtitlesChosen = true
            chooseDefaultSubtitle(embedded)
        }
    }

    /** Comme sur Android : la langue des réglages, sous-titres des modules d'abord, puis pistes du fichier. */
    private fun chooseDefaultSubtitle(embedded: List<TrackChoice>) {
        val preferred = app.settings.settings.value.subtitleLanguage?.let(::normalizeLanguage)
        if (preferred == null) {
            player?.subpictures()?.setTrack(-1)
            selectedSubtitle = null
            return
        }
        val module = subtitles.indexOfFirst { normalizeLanguage(it.lang) == preferred }
        if (module >= 0) {
            selectSubtitle("x:$module")
            return
        }
        val name = languageName(preferred).lowercase()
        embedded.firstOrNull { it.label.lowercase().contains(name) || it.label.lowercase().contains("[$preferred]") }
            ?.let { selectSubtitle(it.key) }
            ?: disableSubtitles()
    }

    private fun moduleLabel() = tr("module", "module")

    private fun tr(fr: String, en: String) = if (app.french) fr else en

    private fun cleanLabel(description: String?): String =
        description?.removePrefix("Track ")?.removePrefix("Piste ")?.ifBlank { null } ?: "—"

    private fun saveProgress(atEnd: Boolean = false) {
        if (!started || released) return
        val position = if (atEnd) durationMs else positionMs
        app.appScope.launch { app.library.recordProgress(snapshot, request.videoId, position, durationMs) }
    }

    fun retry() {
        autoRetries = 0
        val position = if (started) positionMs else request.startPositionMs
        scope.launch { load(position) }
    }

    /** Arrête la lecture et libère VLC, en gardant la dernière position. */
    fun stop() {
        if (released) return
        if (started) {
            val position = if (ended) durationMs else positionMs
            val duration = durationMs
            app.appScope.launch { app.library.recordProgress(snapshot, request.videoId, position, duration) }
        }
        released = true
        app.party.detach(partyPlayer)
        relayLease?.release()
        scope.cancel()
        frames.clear()
        val p = player
        val f = factory
        player = null
        factory = null
        // Libérer VLC peut attendre la fin d'une lecture réseau : jamais sur le fil de l'interface.
        app.appScope.launch(Dispatchers.IO) {
            runCatching {
                p?.events()?.removeMediaPlayerEventListener(events)
                p?.controls()?.stop()
                p?.release()
                f?.release()
            }
        }
    }

    private fun post(block: () -> Unit) {
        if (!released) scope.launch { if (!released) block() }
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 15_000L
        private const val POLL_MS = 200L
        private const val TORRENT_POLL_MS = 1_000L
        private const val MAX_AUTO_RETRIES = 2
        private const val AUTO_RETRY_DELAY_MS = 800L
        const val MAX_VOLUME = 150
        const val RELAY_UNAVAILABLE = "RELAY_UNAVAILABLE"
        const val P2P_UNAVAILABLE = "P2P_UNAVAILABLE"
        const val VLC_MISSING = "VLC_MISSING"
        const val OPEN_FAILED = "OPEN_FAILED"
        const val PLAYBACK_ERROR = "PLAYBACK_ERROR"

        private val localHttp = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .proxy(java.net.Proxy.NO_PROXY)
            .build()

        private val subtitleHttp = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .proxySelector(LocalProxySelector)
            .build()
    }
}
