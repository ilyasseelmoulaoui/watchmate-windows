package app.agregateur.core.title

import app.agregateur.core.addon.AddonClient
import app.agregateur.core.addon.AddonError
import app.agregateur.core.addon.AddonResult
import app.agregateur.core.addon.UrlPolicy
import app.agregateur.core.addon.supports
import app.agregateur.core.model.Meta
import app.agregateur.core.model.Stream
import app.agregateur.core.model.Subtitle
import app.agregateur.core.model.Video
import app.agregateur.core.modules.InstalledModule
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface MetaOutcome {
    data class Loaded(val meta: Meta, val moduleName: String) : MetaOutcome
    data object NotFound : MetaOutcome
    data class Failed(val error: AddonError) : MetaOutcome
}

data class ModuleStreams(
    val moduleUrl: String,
    val moduleName: String,
    val result: AddonResult<List<Stream>>,
)

class TitleService(private val client: AddonClient) {
    /**
     * Fiche d'un titre. Le module qui a fourni le titre est interrogé en premier ; les autres modules
     * qui déclarent la ressource `meta` pour ce type et cet identifiant servent de repli (R3).
     */
    suspend fun meta(
        modules: List<InstalledModule>,
        type: String,
        id: String,
        preferredModuleUrl: String? = null,
    ): MetaOutcome {
        val candidates = modules
            .filter { it.manifest.supports("meta", type, id) }
            .sortedByDescending { it.manifestUrl == preferredModuleUrl }
        var lastError: AddonError? = null
        for (module in candidates) {
            when (val result = client.meta(module.manifestUrl, type, id)) {
                is AddonResult.Success -> result.value?.let { return MetaOutcome.Loaded(it, module.manifest.name) }
                is AddonResult.Failure -> lastError = result.error
            }
        }
        return lastError?.let { MetaOutcome.Failed(it) } ?: MetaOutcome.NotFound
    }

    /** Modules à interroger pour les sources d'une vidéo (R3). */
    fun streamModules(modules: List<InstalledModule>, type: String, videoId: String): List<InstalledModule> =
        modules.filter { it.manifest.supports("stream", type, videoId) }

    suspend fun streamsOf(module: InstalledModule, type: String, videoId: String): ModuleStreams =
        ModuleStreams(
            module.manifestUrl,
            module.manifest.name,
            client.streams(module.manifestUrl, type, videoId),
        )

    /**
     * Sous-titres d'une vidéo : ceux que porte la source, plus ceux des modules qui déclarent la ressource.
     * Ne bloque jamais la lecture : les erreurs sont ignorées et, après `timeoutMs`, on garde ceux de la source.
     * Seules les adresses chiffrées ou locales sont conservées ; les doublons sont retirés.
     */
    suspend fun subtitles(
        modules: List<InstalledModule>,
        type: String,
        videoId: String,
        stream: Stream? = null,
        timeoutMs: Long = 5_000,
    ): List<Subtitle> {
        val own = stream?.subtitles.orEmpty()
        val extra = buildMap {
            stream?.behaviorHints?.filename?.let { put("filename", it) }
            stream?.behaviorHints?.videoSize?.let { put("videoSize", it.toString()) }
        }
        val fromModules = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                modules.filter { it.manifest.supports("subtitles", type, videoId) }
                    .map { module ->
                        async {
                            (client.subtitles(module.manifestUrl, type, videoId, extra) as? AddonResult.Success)
                                ?.value.orEmpty()
                        }
                    }
                    .awaitAll()
                    .flatten()
            }
        }.orEmpty()
        return (own + fromModules)
            .filter { UrlPolicy.isSecureOrLocal(it.url) }
            .distinctBy { it.url }
    }

    /** Les modules sont interrogés en parallèle ; chaque réponse (ou erreur) est émise dès qu'elle arrive. */
    fun streams(modules: List<InstalledModule>, type: String, videoId: String): Flow<ModuleStreams> = channelFlow {
        streamModules(modules, type, videoId).forEach { module ->
            launch { send(streamsOf(module, type, videoId)) }
        }
    }
}

data class Season(val number: Int?, val episodes: List<Video>)

/** Saisons dans l'ordre : numérotées, puis la saison 0 (épisodes spéciaux), puis les vidéos sans saison. */
fun Meta.seasons(): List<Season> =
    videos.groupBy { it.season }
        .map { (number, episodes) -> Season(number, episodes.sortedBy { it.episode ?: Int.MAX_VALUE }) }
        .sortedWith(compareBy<Season>({ rank(it.number) }, { it.number ?: 0 }))

private fun rank(season: Int?): Int = when (season) {
    null -> 2
    0 -> 1
    else -> 0
}
