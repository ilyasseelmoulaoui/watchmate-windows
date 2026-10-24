package app.agregateur.core.playback

import app.agregateur.core.addon.UrlPolicy
import app.agregateur.core.model.Stream

enum class SourceKind { DIRECT, PLATFORM_VIDEO, EXTERNAL_LINK, TORRENT, NZB, ARCHIVE, UNKNOWN }

sealed interface Route {
    data class PlayDirect(val url: String, val headers: Map<String, String>) : Route
    data class PlayPlatform(val videoId: String) : Route
    data class OpenExternalLink(val url: String) : Route
    data class PlayViaRelay(val target: RelayTarget) : Route

    /** Adresse directe non chiffrée vers un hôte distant : refusée (SPEC §7). */
    data class Insecure(val url: String) : Route
    data class Unplayable(val kind: SourceKind) : Route
}

fun Stream.kind(): SourceKind = when {
    url != null -> SourceKind.DIRECT
    ytId != null -> SourceKind.PLATFORM_VIDEO
    externalUrl != null -> SourceKind.EXTERNAL_LINK
    infoHash != null -> SourceKind.TORRENT
    nzbUrl != null -> SourceKind.NZB
    rarUrls != null || zipUrls != null || sevenZipUrls != null || tgzUrls != null || tarUrls != null ->
        SourceKind.ARCHIVE
    else -> SourceKind.UNKNOWN
}

private val platformVideoId = Regex("^[A-Za-z0-9_-]{6,20}$")

/**
 * Règle R5 : seules les sources directes, de plateforme et les liens externes se passent du relais ;
 * torrents et archives lisibles sans décompression passent par lui. NZB, 7z et tgz restent illisibles.
 * Les adresses viennent des modules et ne sont pas fiables : seules les adresses http(s) sont acceptées
 * (jamais `file:`, `content:`, `intent:`…), et le trafic non chiffré vers l'extérieur est refusé.
 */
fun route(stream: Stream): Route = when (val kind = stream.kind()) {
    SourceKind.DIRECT -> {
        val url = requireNotNull(stream.url)
        when {
            UrlPolicy.isSecureOrLocal(url) ->
                Route.PlayDirect(url, stream.behaviorHints.proxyHeaders?.request.orEmpty())
            UrlPolicy.isWebUrl(url) -> Route.Insecure(url)
            else -> Route.Unplayable(kind)
        }
    }
    SourceKind.PLATFORM_VIDEO -> {
        val id = requireNotNull(stream.ytId)
        if (platformVideoId.matches(id)) Route.PlayPlatform(id) else Route.Unplayable(kind)
    }
    SourceKind.EXTERNAL_LINK -> {
        val url = requireNotNull(stream.externalUrl)
        if (UrlPolicy.isWebUrl(url)) Route.OpenExternalLink(url) else Route.Unplayable(kind)
    }
    SourceKind.TORRENT -> torrentTarget(stream)?.let(Route::PlayViaRelay) ?: Route.Unplayable(kind)
    SourceKind.ARCHIVE -> archiveTarget(stream)?.let(Route::PlayViaRelay) ?: Route.Unplayable(kind)
    SourceKind.NZB, SourceKind.UNKNOWN -> Route.Unplayable(kind)
}
