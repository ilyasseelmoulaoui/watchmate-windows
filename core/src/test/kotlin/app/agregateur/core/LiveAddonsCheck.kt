package app.agregateur.core

import app.agregateur.core.addon.AddonClient
import app.agregateur.core.addon.AddonResult
import app.agregateur.core.catalog.CatalogService
import app.agregateur.core.catalog.browsableCatalogs
import app.agregateur.core.catalog.searchableCatalogs
import app.agregateur.core.modules.InMemoryModuleStore
import app.agregateur.core.modules.InstallResult
import app.agregateur.core.modules.ModuleRepository
import app.agregateur.core.modules.SUGGESTED_MODULES
import app.agregateur.core.playback.route
import app.agregateur.core.title.MetaOutcome
import app.agregateur.core.title.TitleService
import app.agregateur.core.title.seasons
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Parcours complet avec les vrais modules proposés (réseau). Ignoré sauf si LIVE_ADDONS=1 :
 * `$env:LIVE_ADDONS=1; .\gradlew.bat :core:test --tests '*LiveAddonsCheck*' -i`
 */
class LiveAddonsCheck {
    @Test
    fun `modules proposes, parcours complet`() = runBlocking<Unit> {
        assumeTrue(System.getenv("LIVE_ADDONS") == "1")
        val client = AddonClient(AddonClient.defaultHttpClient())
        val repo = ModuleRepository(client, InMemoryModuleStore())
        repo.load()
        SUGGESTED_MODULES.forEach { s ->
            val r = repo.install(s.manifestUrl)
            println("INSTALL ${s.name}: ${if (r is InstallResult.Installed) "ok" else r}")
        }
        val modules = repo.modules.value
        val catalogs = CatalogService(client)
        val titles = TitleService(client)

        val firstItems = mutableMapOf<String, Pair<String, String>>()
        browsableCatalogs(modules).forEach { ref ->
            when (val page = catalogs.page(ref)) {
                is AddonResult.Success -> {
                    val items = page.value
                    val insecurePosters = items.count { it.poster?.startsWith("http://") == true }
                    println("CATALOG ${ref.moduleName} / ${ref.type} / ${ref.name}: ${items.size} éléments, genres=${ref.genres.size}, affiches http=$insecurePosters, ex=${items.firstOrNull()?.let { "${it.id} ${it.name}" }}")
                    items.firstOrNull()?.let { firstItems.putIfAbsent("${ref.moduleName}|${ref.type}", it.type to it.id) }
                }
                is AddonResult.Failure -> println("CATALOG ${ref.moduleName} / ${ref.type} / ${ref.name}: ERREUR ${page.error}")
            }
        }

        firstItems.forEach { (key, value) ->
            val (type, id) = value
            val meta = titles.meta(modules, type, id, null)
            val loaded = (meta as? MetaOutcome.Loaded)?.meta
            println("META [$key] $type $id -> ${meta::class.simpleName} ${(meta as? MetaOutcome.Failed)?.error ?: ""} ${loaded?.name ?: ""} vidéos=${loaded?.videos?.size ?: 0} saisons=${loaded?.seasons()?.size ?: 0}")
            val videoId = loaded?.videos?.firstOrNull { (it.season ?: 0) > 0 }?.id ?: id
            titles.streamModules(modules, type, videoId).forEach { m ->
                when (val s = titles.streamsOf(m, type, videoId).result) {
                    is AddonResult.Success -> println("  STREAMS ${m.manifest.name} $videoId: ${s.value.size} → ${s.value.groupingBy { route(it)::class.simpleName }.eachCount()}")
                    is AddonResult.Failure -> println("  STREAMS ${m.manifest.name} $videoId: ERREUR ${s.error}")
                }
            }
            val subs = titles.subtitles(modules, type, videoId)
            println("  SUBS $videoId: ${subs.size} langues=${subs.map { it.lang }.distinct().take(12)}")
        }

        searchableCatalogs(modules).forEach { ref ->
            when (val r = catalogs.search(ref, "batman")) {
                is AddonResult.Success -> println("SEARCH ${ref.moduleName} / ${ref.type}: ${r.value.size}")
                is AddonResult.Failure -> println("SEARCH ${ref.moduleName} / ${ref.type}: ERREUR ${r.error}")
            }
        }
    }
}
