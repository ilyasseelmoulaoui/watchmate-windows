package app.agregateur.ui

import app.agregateur.core.addon.AddonError
import app.agregateur.core.playback.RelayTarget
import app.agregateur.core.playback.Route

fun typeLabel(type: String, french: Boolean): String = when (type) {
    "movie" -> if (french) "Films" else "Movies"
    "series" -> if (french) "Séries" else "Series"
    "anime" -> "Anime"
    "channel" -> if (french) "Chaînes" else "Channels"
    "tv" -> "TV"
    else -> type.replaceFirstChar { it.uppercase() }
}

fun addonErrorMessage(error: AddonError, french: Boolean): String = when (error) {
    AddonError.InvalidUrl -> if (french) "Adresse invalide." else "Invalid address."
    AddonError.InsecureNotAllowed -> if (french) "Adresse non chiffrée refusée." else "Insecure address refused."
    is AddonError.Unreachable -> if (french) "Injoignable." else "Unreachable."
    is AddonError.HttpStatus -> if (french) "Erreur HTTP ${error.code}." else "HTTP error ${error.code}."
    is AddonError.InvalidResponse -> if (french) "Réponse invalide." else "Invalid response."
}

fun sourceKindLabel(route: Route, french: Boolean): String = when (route) {
    is Route.PlayDirect -> if (french) "Direct" else "Direct"
    is Route.PlayPlatform -> if (french) "Plateforme" else "Platform"
    is Route.OpenExternalLink -> if (french) "Lien externe" else "External link"
    is Route.PlayViaRelay -> when (val target = route.target) {
        is RelayTarget.Torrent -> "Torrent"
        is RelayTarget.Archive -> if (french) "Archive (${target.kind.name})" else "Archive (${target.kind.name})"
    }
    is Route.Insecure -> if (french) "Non chiffré (refusé)" else "Insecure (blocked)"
    is Route.Unplayable -> if (french) "Illisible" else "Unplayable"
}

/** « S2 É3 » pour un identifiant d'épisode `id:saison:épisode` ; null pour un film. */
fun episodeCode(videoId: String): String? {
    val parts = videoId.split(':')
    if (parts.size < 3) return null
    val season = parts[parts.size - 2].toIntOrNull() ?: return null
    val episode = parts.last().toIntOrNull() ?: return null
    return "S$season É$episode"
}

fun seasonLabel(number: Int?, french: Boolean): String = when (number) {
    null -> if (french) "Autres" else "Other"
    0 -> if (french) "Spéciaux" else "Specials"
    else -> if (french) "Saison $number" else "Season $number"
}
