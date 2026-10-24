package app.agregateur.party

import app.agregateur.core.addon.AddonResult
import app.agregateur.core.model.Stream
import app.agregateur.core.modules.ModuleRepository
import app.agregateur.core.party.PartyMedia
import app.agregateur.core.playback.DecoderCaps
import app.agregateur.core.playback.RelayTarget
import app.agregateur.core.playback.Route
import app.agregateur.core.playback.fits
import app.agregateur.core.playback.qualityPenalty
import app.agregateur.core.playback.route
import app.agregateur.core.playback.traits
import app.agregateur.core.title.TitleService
import app.agregateur.player.PlayRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Résout une source jouable localement pour le média de la salle.
 * Chaque appareil utilise ses propres modules (pas l'URL de l'hôte).
 */
object PartyAutoJoin {
    sealed interface Result {
        data class Ready(val request: PlayRequest, val needsP2pNotice: Boolean) : Result
        data object NoSource : Result
    }

    suspend fun resolve(
        media: PartyMedia,
        modules: ModuleRepository,
        titles: TitleService,
        startPositionMs: Long,
        timeoutMs: Long = RESOLVE_TIMEOUT_MS,
        caps: DecoderCaps = DESKTOP_CAPS,
    ): Result = coroutineScope {
        val installed = modules.modules.value
        val candidates = titles.streamModules(installed, media.type, media.videoId)
        if (candidates.isEmpty()) return@coroutineScope Result.NoSource

        val collected = withTimeoutOrNull(timeoutMs) {
            candidates.map { module ->
                async {
                    runCatching { titles.streamsOf(module, media.type, media.videoId) }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }.orEmpty()

        val playable = collected.flatMap { group ->
            val streams = (group.result as? AddonResult.Success)?.value.orEmpty()
            streams.mapNotNull { stream ->
                when (val r = route(stream)) {
                    is Route.PlayDirect -> Candidate(stream, r.url, r.headers, null, p2p = false, rank = 0)
                    is Route.PlayViaRelay -> Candidate(stream, "", emptyMap(), r.target, p2p = r.target is RelayTarget.Torrent, rank = 1)
                    else -> null
                }
            }
        }

        // Un 4K HEVC 10 bits pris au hasard échoue au décodage (ERROR_CODE_DECODING_FAILED) sur les appareils modestes.
        val best = playable
            .map { it to it.stream.traits() }
            .filter { (_, t) -> t.fits(caps) }
            .sortedWith(compareBy({ it.first.rank }, { it.second.qualityPenalty() }))
            .firstOrNull()?.first
            ?: return@coroutineScope Result.NoSource
        Result.Ready(
            PlayRequest(
                url = best.url,
                headers = best.headers,
                type = media.type,
                videoId = media.videoId,
                stream = best.stream,
                title = media.title,
                titleId = media.id,
                poster = media.poster,
                moduleUrl = media.moduleUrl,
                startPositionMs = startPositionMs,
                relayTarget = best.target,
            ),
            needsP2pNotice = best.p2p,
        )
    }

    private data class Candidate(
        val stream: Stream,
        val url: String,
        val headers: Map<String, String>,
        val target: RelayTarget?,
        val p2p: Boolean,
        val rank: Int,
    )

    private const val RESOLVE_TIMEOUT_MS = 12_000L
}

/** VLC décode tout en logiciel s'il le faut : seule la 4K HEVC 10 bits reste lourde, on la tente quand même. */
private val DESKTOP_CAPS = DecoderCaps(maxAvcHeight = 2160, maxHevcHeight = 2160, hevc10Bit = true)
