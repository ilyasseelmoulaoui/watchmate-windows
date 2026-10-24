package app.agregateur.core

import app.agregateur.core.addon.AddonClient
import app.agregateur.core.addon.AddonError
import app.agregateur.core.addon.AddonResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class AddonClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = AddonClient()
    private val base get() = "http://127.0.0.1:${server.port}"

    @AfterTest
    fun stop() = server.shutdown()

    private fun <T> AddonResult<T>.value(): T = (this as AddonResult.Success<T>).value
    private fun AddonResult<*>.error(): AddonError = (this as AddonResult.Failure).error

    @Test
    fun `lit un manifeste`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"id":"a.b","name":"N","version":"1","resources":["stream"],"types":["movie"],"catalogs":[]}"""))
        val manifest = client.manifest("$base/manifest.json").value()
        assertEquals("a.b", manifest.id)
        assertEquals("/manifest.json", server.takeRequest().path)
    }

    @Test
    fun `liste de sources et chemin demande`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"streams":[{"url":"https://x/v.mp4","name":"HD"},{"infoHash":"abc","fileIdx":2}]}"""))
        val streams = client.streams(base, "series", "tt1:1:2").value()
        assertEquals(2, streams.size)
        assertEquals(2, streams[1].fileIdx)
        assertEquals("/stream/series/tt1:1:2.json", server.takeRequest().path)
    }

    @Test
    fun `catalogue avec extra`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"metas":[{"id":"tt1","type":"movie","name":"T","poster":null}]}"""))
        val metas = client.catalog(base, "movie", "top", mapOf("skip" to "100")).value()
        assertEquals("tt1", metas.single().id)
        assertEquals("/catalog/movie/top/skip=100.json", server.takeRequest().path)
    }

    @Test
    fun `fiche vide donne null`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"meta":{}}"""))
        assertNull(client.meta(base, "movie", "tt1").value())
    }

    @Test
    fun `statut http en erreur`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(AddonError.HttpStatus(404), client.streams(base, "movie", "tt1").error())
    }

    @Test
    fun `reponse invalide`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("pas du json"))
        assertIs<AddonError.InvalidResponse>(client.streams(base, "movie", "tt1").error())
    }

    @Test
    fun `module injoignable`() = runBlocking<Unit> {
        val port = server.port
        server.shutdown()
        assertIs<AddonError.Unreachable>(client.manifest("http://127.0.0.1:$port/manifest.json").error())
    }

    @Test
    fun `http vers un hote distant refuse sauf accord explicite`() = runBlocking<Unit> {
        assertEquals(
            AddonError.InsecureNotAllowed,
            client.manifest("http://exemple.org/manifest.json").error(),
        )
        assertEquals(AddonError.InvalidUrl, client.manifest("n importe quoi").error())
    }
}
