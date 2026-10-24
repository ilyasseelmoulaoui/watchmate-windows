package app.agregateur.core.catalog

import app.agregateur.core.addon.AddonClient
import app.agregateur.core.addon.AddonError
import app.agregateur.core.addon.AddonResult
import app.agregateur.core.addon.supports
import app.agregateur.core.model.MetaPreview
import app.agregateur.core.modules.InstalledModule

/** Un catalogue déclaré par un module installé et consultable sans saisie (aucun argument obligatoire). */
data class CatalogRef(
    val moduleUrl: String,
    val moduleName: String,
    val type: String,
    val catalogId: String,
    val name: String,
    val genres: List<String> = emptyList(),
) {
    val key: String get() = "$moduleUrl|$type|$catalogId"
}

/**
 * Règle R3 : seuls les catalogues d'un module qui déclare la ressource `catalog` pour ce type sont retenus.
 * Les catalogues dont un argument est obligatoire (une recherche, par exemple) sont exclus de la navigation.
 */
fun browsableCatalogs(modules: List<InstalledModule>): List<CatalogRef> =
    modules.flatMap { module ->
        module.manifest.catalogs
            .filter { decl ->
                decl.extra.none { it.isRequired } && module.manifest.supports("catalog", decl.type)
            }
            .map { decl ->
                CatalogRef(
                    moduleUrl = module.manifestUrl,
                    moduleName = module.manifest.name,
                    type = decl.type,
                    catalogId = decl.id,
                    name = decl.name.ifBlank { decl.id },
                    genres = decl.extra.firstOrNull { it.name == "genre" }?.options.orEmpty(),
                )
            }
    }

/**
 * Catalogues interrogeables par une recherche : ils déclarent l'argument `search` et n'en exigent pas d'autre (R3).
 */
fun searchableCatalogs(modules: List<InstalledModule>): List<CatalogRef> =
    modules.flatMap { module ->
        module.manifest.catalogs
            .filter { decl ->
                decl.extra.any { it.name == "search" } &&
                    decl.extra.none { it.isRequired && it.name != "search" } &&
                    module.manifest.supports("catalog", decl.type)
            }
            .map { decl ->
                CatalogRef(
                    moduleUrl = module.manifestUrl,
                    moduleName = module.manifest.name,
                    type = decl.type,
                    catalogId = decl.id,
                    name = decl.name.ifBlank { decl.id },
                )
            }
    }

/** Texte de recherche envoyé aux modules : espaces retirés aux extrémités, longueur bornée ; null s'il est vide. */
fun normalizeQuery(text: String): String? =
    text.trim().replace(Regex("\\s+"), " ").take(MAX_QUERY_LENGTH).trimEnd().ifEmpty { null }

const val MAX_QUERY_LENGTH = 100

class CatalogService(private val client: AddonClient) {
    suspend fun search(ref: CatalogRef, query: String): AddonResult<List<MetaPreview>> =
        client.catalog(ref.moduleUrl, ref.type, ref.catalogId, mapOf("search" to query))

    /** `skip` = nombre d'éléments déjà reçus ; `genre` n'est envoyé que si le catalogue le propose. */
    suspend fun page(ref: CatalogRef, skip: Int = 0, genre: String? = null): AddonResult<List<MetaPreview>> {
        val extra = linkedMapOf<String, String>()
        if (genre != null && genre in ref.genres) extra["genre"] = genre
        if (skip > 0) extra["skip"] = skip.toString()
        return client.catalog(ref.moduleUrl, ref.type, ref.catalogId, extra)
    }
}

/**
 * Pagination d'un catalogue. La documentation publique annonce une page de 100 éléments, mais les modules
 * réels varient : on s'arrête quand une page est vide ou ne contient que des éléments déjà vus.
 * Non thread-safe : un seul chargement à la fois.
 */
class CatalogPager(
    private val service: CatalogService,
    val ref: CatalogRef,
    val genre: String? = null,
) {
    private val seen = HashSet<String>()
    private var fetched = 0

    var items: List<MetaPreview> = emptyList()
        private set
    var endReached: Boolean = false
        private set

    /** Renvoie l'erreur éventuelle ; en cas d'erreur, l'état est inchangé et on peut réessayer. */
    suspend fun loadMore(): AddonError? {
        if (endReached) return null
        when (val result = service.page(ref, skip = fetched, genre = genre)) {
            is AddonResult.Failure -> return result.error
            is AddonResult.Success -> {
                fetched += result.value.size
                val fresh = result.value.filter { seen.add("${it.type}:${it.id}") }
                if (fresh.isEmpty()) endReached = true else items = items + fresh
            }
        }
        return null
    }
}
