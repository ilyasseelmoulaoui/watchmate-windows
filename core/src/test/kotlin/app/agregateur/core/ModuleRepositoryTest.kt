package app.agregateur.core

import app.agregateur.core.addon.AddonClient
import app.agregateur.core.addon.AddonError
import app.agregateur.core.modules.FileModuleStore
import app.agregateur.core.modules.InMemoryModuleStore
import app.agregateur.core.modules.InstallError
import app.agregateur.core.modules.InstallResult
import app.agregateur.core.modules.InstalledModule
import app.agregateur.core.modules.ModuleRepository
import app.agregateur.core.modules.ModuleStore
import app.agregateur.core.modules.Origin
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class ModuleRepositoryTest {
    private val server = MockWebServer().apply { start() }
    private val address get() = "http://127.0.0.1:${server.port}/manifest.json"
    private val manifestJson =
        """{"id":"a.b","name":"Module A","version":"1.2.3","description":"d","resources":["stream"],"types":["movie"],"catalogs":[]}"""

    private fun repo(store: ModuleStore = InMemoryModuleStore()) = ModuleRepository(AddonClient(), store, clock = { 42L })

    @AfterTest
    fun stop() = server.shutdown()

    @Test
    fun `r1 adresse vide refusee sans appel reseau`() = runBlocking<Unit> {
        val result = repo().install("   ")
        assertEquals(InstallResult.Failed(InstallError.EmptyAddress), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `r1 espaces retires puis module installe et enregistre`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(manifestJson))
        val store = InMemoryModuleStore()
        val repository = repo(store)
        val result = repository.install("  $address  ")
        val module = assertIs<InstallResult.Installed>(result).module
        assertEquals("Module A", module.manifest.name)
        assertEquals(Origin.ADDRESS, module.origin)
        assertEquals(42L, module.installedAt)
        assertEquals(listOf(module), repository.modules.value)
        assertEquals(listOf(module), store.load())
    }

    @Test
    fun `r2 echec reseau n installe rien`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(500))
        val store = InMemoryModuleStore()
        val repository = repo(store)
        val result = repository.install(address)
        assertEquals(InstallResult.Failed(InstallError.Addon(AddonError.HttpStatus(500))), result)
        assertTrue(repository.modules.value.isEmpty())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `manifeste invalide n installe rien`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"pas":"un manifeste"}"""))
        val repository = repo()
        assertIs<InstallResult.Failed>(repository.install(address))
        assertTrue(repository.modules.value.isEmpty())
    }

    @Test
    fun `deuxieme installation de la meme adresse`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(manifestJson))
        val repository = repo()
        repository.install(address)
        val again = repository.install(address.removeSuffix("manifest.json"))
        assertIs<InstallResult.AlreadyInstalled>(again)
        assertEquals(1, repository.modules.value.size)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `echec d enregistrement`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(manifestJson))
        val failing = object : ModuleStore {
            override suspend fun load() = emptyList<InstalledModule>()
            override suspend fun save(modules: List<InstalledModule>) = throw IOException("disque plein")
        }
        val repository = repo(failing)
        assertEquals(InstallResult.Failed(InstallError.Storage), repository.install(address))
        assertTrue(repository.modules.value.isEmpty())
    }

    @Test
    fun `retrait`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(manifestJson))
        val repository = repo()
        val module = assertIs<InstallResult.Installed>(repository.install(address)).module
        assertTrue(repository.remove(module.manifestUrl))
        assertTrue(repository.modules.value.isEmpty())
    }

    @Test
    fun `le fichier survit a un redemarrage`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(manifestJson))
        val file = File.createTempFile("modules", ".json").also { it.delete(); it.deleteOnExit() }
        val first = repo(FileModuleStore(file))
        val module = assertIs<InstallResult.Installed>(first.install(address)).module

        val second = repo(FileModuleStore(file))
        second.load()
        assertEquals(listOf(module), second.modules.value)
    }

    @Test
    fun `fichier corrompu mis de cote`() = runBlocking<Unit> {
        val file = File.createTempFile("modules", ".json").also { it.deleteOnExit() }
        file.writeText("{ pas du json")
        val repository = repo(FileModuleStore(file))
        repository.load()
        assertTrue(repository.modules.value.isEmpty())
        assertTrue(File(file.path + ".bak").also { it.deleteOnExit() }.exists())
    }
}
