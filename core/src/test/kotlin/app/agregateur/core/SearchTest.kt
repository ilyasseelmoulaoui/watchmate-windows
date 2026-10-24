package app.agregateur.core

import app.agregateur.core.addon.AddonClient
import app.agregateur.core.addon.AddonJson
import app.agregateur.core.addon.AddonResult
import app.agregateur.core.catalog.CatalogService
import app.agregateur.core.catalog.MAX_QUERY_LENGTH
import app.agregateur.core.catalog.normalizeQuery
import app.agregateur.core.catalog.searchableCatalogs
import app.agregateur.core.model.Manifest
import app.agregateur.core.modules.InstalledModule
import app.agregateur.core.modules.Origin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class SearchTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun stop() = server.shutdown()

    private fun installed(manifestJson: String, url: String = "http://127.0.0.1:${server.port}/manifest.json") =
        InstalledModule(url, AddonJson.decodeFromString<Manifest>(manifestJson), Origin.ADDRESS, 0)

    private val manifest = """
        {"id":"a","name":"Module A","version":"1","types":["movie","series"],"resources":["catalog"],
         "catalogs":[
           {"type":"movie","id":"top","name":"Top"},
           {"type":"movie","id":"find","name":"Recherche films","extra":[{"name":"search","isRequired":true}]},
           {"type":"series","id":"both","name":"Séries","extra":[{"name":"search"},{"name":"skip"}]},
           {"type":"movie","id":"genre-search","name":"G","extra":[{"name":"search","isRequired":true},{"name":"genre","isRequired":true}]},
           {"type":"tv","id":"tvsearch","name":"TV","extra":[{"name":"search"}]}
         ]}
    """

    @Test
    fun `catalogues de recherche retenus`() {
        val refs = searchableCatalogs(listOf(installed(manifest)))
        assertEquals(listOf("find", "both"), refs.map { it.catalogId })
        assertEquals("Module A", refs.first().moduleName)
    }

    @Test
    fun `module sans ressource catalog exclu`() {
        assertTrue(searchableCatalogs(listOf(installed(manifest.replace("\"catalog\"", "\"meta\"")))).isEmpty())
    }

    @Test
    fun `texte de recherche normalise`() {
        assertNull(normalizeQuery("   "))
        assertEquals("game of thrones", normalizeQuery("  game   of\tthrones "))
        assertEquals(MAX_QUERY_LENGTH, normalizeQuery("x".repeat(500))!!.length)
    }

    @Test
    fun `requete encodee comme le protocole public`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"metas":[{"id":"tt1","type":"movie","name":"Été"}]}"""))
        val ref = searchableCatalogs(listOf(installed(manifest))).first()
        val result = CatalogService(AddonClient()).search(ref, "été à Paris & co")
        assertEquals("Été", assertIs<AddonResult.Success<List<app.agregateur.core.model.MetaPreview>>>(result).value.single().name)
        assertEquals("/catalog/movie/find/search=%C3%A9t%C3%A9%20%C3%A0%20Paris%20%26%20co.json", server.takeRequest().path)
    }
}
