package app.agregateur.core

import app.agregateur.core.addon.AddonClient
import app.agregateur.core.addon.AddonError
import app.agregateur.core.addon.AddonJson
import app.agregateur.core.catalog.CatalogPager
import app.agregateur.core.catalog.CatalogRef
import app.agregateur.core.catalog.CatalogService
import app.agregateur.core.catalog.browsableCatalogs
import app.agregateur.core.model.Manifest
import app.agregateur.core.modules.InMemoryModuleStore
import app.agregateur.core.modules.InstalledModule
import app.agregateur.core.modules.ModuleRepository
import app.agregateur.core.modules.Origin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class CatalogsTest {
    private val server = MockWebServer().apply { start() }
    private val moduleUrl get() = "http://127.0.0.1:${server.port}/manifest.json"

    @AfterTest
    fun stop() = server.shutdown()

    private fun installed(manifestJson: String, url: String = "http://127.0.0.1:1/manifest.json") =
        InstalledModule(url, AddonJson.decodeFromString<Manifest>(manifestJson), Origin.ADDRESS, 0)

    private val manifest = """
        {"id":"a","name":"Module A","version":"1","types":["movie","series"],"resources":["catalog","stream"],
         "catalogs":[
           {"type":"movie","id":"top","name":"Top films","extra":[{"name":"genre","options":["Action","Drame"]},{"name":"skip"}]},
           {"type":"series","id":"new","name":""},
           {"type":"movie","id":"search","name":"Recherche","extra":[{"name":"search","isRequired":true}]},
           {"type":"tv","id":"live","name":"Direct"}
         ]}
    """

    @Test
    fun `catalogues navigables exclut les arguments obligatoires et les types non declares`() {
        val refs = browsableCatalogs(listOf(installed(manifest)))
        assertEquals(listOf("top", "new"), refs.map { it.catalogId })
        assertEquals("new", refs[1].name)
        assertEquals(listOf("Action", "Drame"), refs[0].genres)
        assertEquals("Module A", refs[0].moduleName)
    }

    @Test
    fun `module sans ressource catalog n est pas interroge`() {
        val sansCatalog = manifest.replace("\"catalog\",", "")
        assertTrue(browsableCatalogs(listOf(installed(sansCatalog))).isEmpty())
    }

    private fun ref() = browsableCatalogs(listOf(installed(manifest, moduleUrl))).first()

    @Test
    fun `page envoie skip et genre seulement s ils sont valides`() = runBlocking<Unit> {
        val service = CatalogService(AddonClient())
        repeat(3) { server.enqueue(MockResponse().setBody("""{"metas":[]}""")) }
        service.page(ref())
        service.page(ref(), skip = 100, genre = "Action")
        service.page(ref(), skip = 0, genre = "Inconnu")
        assertEquals("/catalog/movie/top.json", server.takeRequest().path)
        assertEquals("/catalog/movie/top/genre=Action&skip=100.json", server.takeRequest().path)
        assertEquals("/catalog/movie/top.json", server.takeRequest().path)
    }

    private fun metas(vararg ids: String) =
        """{"metas":[${ids.joinToString(",") { """{"id":"$it","type":"movie","name":"N$it"}""" }}]}"""

    @Test
    fun `le pager enchaine les pages puis s arrete sur une page vide`() = runBlocking<Unit> {
        val pager = CatalogPager(CatalogService(AddonClient()), ref())
        server.enqueue(MockResponse().setBody(metas("1", "2")))
        server.enqueue(MockResponse().setBody(metas("3")))
        server.enqueue(MockResponse().setBody(metas()))
        assertNull(pager.loadMore())
        assertNull(pager.loadMore())
        assertNull(pager.loadMore())
        assertEquals(listOf("1", "2", "3"), pager.items.map { it.id })
        assertTrue(pager.endReached)
        assertEquals("/catalog/movie/top.json", server.takeRequest().path)
        assertEquals("/catalog/movie/top/skip=2.json", server.takeRequest().path)
        assertEquals("/catalog/movie/top/skip=3.json", server.takeRequest().path)
        assertNull(pager.loadMore())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `le pager s arrete si le module ignore skip et renvoie les memes elements`() = runBlocking<Unit> {
        val pager = CatalogPager(CatalogService(AddonClient()), ref())
        repeat(2) { server.enqueue(MockResponse().setBody(metas("1", "2"))) }
        pager.loadMore()
        pager.loadMore()
        assertEquals(2, pager.items.size)
        assertTrue(pager.endReached)
    }

    @Test
    fun `une erreur laisse l etat inchange et permet de reessayer`() = runBlocking<Unit> {
        val pager = CatalogPager(CatalogService(AddonClient()), ref())
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(metas("1")))
        assertEquals(AddonError.HttpStatus(503), pager.loadMore())
        assertTrue(pager.items.isEmpty())
        assertEquals(false, pager.endReached)
        assertNull(pager.loadMore())
        assertEquals(listOf("1"), pager.items.map { it.id })
    }

    @Test
    fun `installer avant le chargement n ecrase pas la liste enregistree`() = runBlocking<Unit> {
        val existing = installed(manifest, "https://deja.exemple/manifest.json")
        val store = InMemoryModuleStore(listOf(existing))
        val repository = ModuleRepository(AddonClient(), store)
        server.enqueue(MockResponse().setBody("""{"id":"n","name":"Nouveau","version":"1","types":[],"resources":[],"catalogs":[]}"""))
        repository.install(moduleUrl)
        assertEquals(2, store.load().size)
        assertEquals(existing, store.load().first())
    }
}
