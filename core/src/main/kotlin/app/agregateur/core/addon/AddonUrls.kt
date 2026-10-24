package app.agregateur.core.addon

import app.agregateur.core.model.Manifest
import java.net.URLEncoder
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object AddonUrls {
    const val MANIFEST = "manifest.json"
    private const val STREMIO_SCHEME = "stremio://"

    /**
     * Racine d'un module : retire `manifest.json`, la requête et le fragment. Null si l'adresse n'est pas http(s).
     * Les liens d'installation `stremio://hôte/…` de l'écosystème désignent la même adresse en https.
     */
    fun base(addonUrl: String): HttpUrl? {
        val trimmed = addonUrl.trim()
        val web = if (trimmed.startsWith(STREMIO_SCHEME, ignoreCase = true)) "https://" + trimmed.substring(STREMIO_SCHEME.length) else trimmed
        val url = web.toHttpUrlOrNull() ?: return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (segments.lastOrNull() == MANIFEST) segments.removeAt(segments.lastIndex)
        return url.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .apply {
                segments.forEach(::addPathSegment)
                if (segments.isNotEmpty()) addPathSegment("")
            }
            .build()
    }

    fun manifest(base: HttpUrl): HttpUrl =
        base.newBuilder().addPathSegment(MANIFEST).build()

    /** `/{resource}/{type}/{id}.json`, ou `/{resource}/{type}/{id}/{extra}.json` si `extra` n'est pas vide. */
    fun resource(
        base: HttpUrl,
        resource: String,
        type: String,
        id: String,
        extra: Map<String, String> = emptyMap(),
    ): HttpUrl = base.newBuilder().apply {
        addPathSegment(resource)
        addPathSegment(type)
        if (extra.isEmpty()) {
            addPathSegment("$id.json")
        } else {
            addPathSegment(id)
            addEncodedPathSegment("${encodeExtra(extra)}.json")
        }
    }.build()

    fun encodeExtra(extra: Map<String, String>): String =
        extra.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}

/**
 * Règle R3 : un module n'est interrogé que pour les ressources et types qu'il déclare.
 * Une ressource déclarée comme simple texte hérite des types et préfixes du manifeste ;
 * une ressource objet sans préfixes accepte tous les identifiants ; les préfixes ne filtrent jamais les catalogues.
 */
fun Manifest.supports(resource: String, type: String, id: String? = null): Boolean {
    val decl = resources.firstOrNull { it.name == resource } ?: return false
    val allowedTypes = if (decl.inherits) types else decl.types ?: types
    if (type !in allowedTypes) return false
    if (resource == "catalog") return true
    val prefixes = if (decl.inherits) idPrefixes else decl.idPrefixes
    return prefixes == null || id == null || prefixes.any { id.startsWith(it) }
}
