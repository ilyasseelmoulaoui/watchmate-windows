package app.agregateur.core

import app.agregateur.core.addon.AddonClient
import app.agregateur.core.library.InMemoryLibraryStore
import app.agregateur.core.library.LibraryRepository
import app.agregateur.core.library.TitleSnapshot
import app.agregateur.core.model.Manifest
import app.agregateur.core.modules.InMemoryModuleStore
import app.agregateur.core.modules.InstalledModule
import app.agregateur.core.modules.ModuleRepository
import app.agregateur.core.modules.Origin
import app.agregateur.core.settings.InMemorySettingsStore
import app.agregateur.core.settings.SettingsRepository
import app.agregateur.core.sync.InMemorySyncStateStore
import app.agregateur.core.sync.LibraryCollection
import app.agregateur.core.sync.ModuleCollection
import app.agregateur.core.sync.RemoteItem
import app.agregateur.core.sync.SettingsCollection
import app.agregateur.core.sync.SyncBackend
import app.agregateur.core.sync.SyncEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/** Serveur en mémoire avec la même règle que `sync_push` : une écriture plus ancienne que l'existante est ignorée. */
private class FakeServer : SyncBackend {
    private var seq = 0L
    val rows = linkedMapOf<Pair<String, String>, RemoteItem>()

    override suspend fun pull(afterSeq: Long): List<RemoteItem> = rows.values.filter { it.seq > afterSeq }.sortedBy { it.seq }

    override suspend fun push(items: List<RemoteItem>) {
        items.forEach { item ->
            val existing = rows[item.kind to item.key]
            if (existing == null || item.updatedAt > existing.updatedAt) rows[item.kind to item.key] = item.copy(seq = ++seq)
        }
    }
}

/** Un appareil : ses dépôts en mémoire, son moteur, et une horloge commune réglable. */
private class Device(clock: () -> Long) {
    val library = LibraryRepository(InMemoryLibraryStore(), clock)
    val modules = ModuleRepository(AddonClient(OkHttpClient()), InMemoryModuleStore(), clock)
    val settings = SettingsRepository(InMemorySettingsStore())
    val engine = SyncEngine(listOf(LibraryCollection(library), ModuleCollection(modules), SettingsCollection(settings)), InMemorySyncStateStore(), clock)

    suspend fun sync(server: FakeServer) = engine.sync(USER, server)

    suspend fun changed() = engine.noteLocalChanges()

    companion object {
        const val USER = "u1"
    }
}

class SyncEngineTest {
    private var now = 1_000L
    private val clock = { now }
    private val server = FakeServer()
    private val a = Device(clock)
    private val b = Device(clock)
    private val bunny = TitleSnapshot("movie", "tt1", "Bunny", null, "https://m/manifest.json")
    private val kid = TitleSnapshot("movie", "tt2", "The Kid", null, "https://m/manifest.json")

    @Test
    fun `ajout, progression et retrait passent d un appareil a l autre`() = runBlocking<Unit> {
        a.library.add(bunny); a.changed()
        now += 10
        a.library.recordProgress(bunny, "tt1", 60_000, 600_000); a.changed()
        a.sync(server)
        b.sync(server)
        val onB = b.library.entries.value.single()
        assertTrue(onB.saved)
        assertEquals(60_000, onB.progress?.positionMs)

        now += 10
        b.library.remove("movie", "tt1"); b.library.clearProgress("movie", "tt1"); b.changed()
        assertTrue(b.library.entries.value.isEmpty())
        b.sync(server)
        a.sync(server)
        assertTrue(a.library.entries.value.isEmpty(), "le retrait se propage")
        assertNull(server.rows["library" to "movie:tt1"]?.data)
    }

    @Test
    fun `conflit, la modification la plus recente l emporte`() = runBlocking<Unit> {
        a.library.add(bunny); a.changed(); a.sync(server); b.sync(server)

        now = 2_000; a.library.recordProgress(bunny, "tt1", 10_000, 600_000); a.changed()
        now = 3_000; b.library.recordProgress(bunny, "tt1", 300_000, 600_000); b.changed()
        a.sync(server); b.sync(server); a.sync(server)

        assertEquals(300_000, a.library.entries.value.single().progress?.positionMs)
        assertEquals(300_000, b.library.entries.value.single().progress?.positionMs)
    }

    @Test
    fun `premiere connexion, les deux bibliotheques sont reunies`() = runBlocking<Unit> {
        a.library.add(bunny); a.changed()
        b.library.add(kid); b.changed()
        a.sync(server); b.sync(server); a.sync(server)
        assertEquals(setOf("movie:tt1", "movie:tt2"), a.library.entries.value.map { it.key }.toSet())
        assertEquals(setOf("movie:tt1", "movie:tt2"), b.library.entries.value.map { it.key }.toSet())
    }

    @Test
    fun `modules et reglages synchronises, accord pair a pair laisse a chaque appareil`() = runBlocking<Unit> {
        val module = InstalledModule("https://m/manifest.json", Manifest(id = "m", name = "M"), Origin.LIST, 5)
        a.modules.applyRemote(listOf(module), emptySet())
        a.settings.update { it.copy(subtitleLanguage = "fr", p2pNoticeAccepted = true) }
        a.changed(); a.sync(server); b.sync(server)
        assertEquals(listOf("https://m/manifest.json"), b.modules.modules.value.map { it.manifestUrl })
        assertEquals("fr", b.settings.settings.value.subtitleLanguage)
        assertFalse(b.settings.settings.value.p2pNoticeAccepted)

        now += 10
        b.modules.remove("https://m/manifest.json"); b.changed(); b.sync(server); a.sync(server)
        assertTrue(a.modules.modules.value.isEmpty())
    }

    @Test
    fun `nouvel appareil, ses reglages par defaut n ecrasent pas ceux du compte`() = runBlocking<Unit> {
        a.settings.update { it.copy(subtitleLanguage = "ar") }; a.changed(); a.sync(server)
        now = 999_000
        b.changed()
        b.sync(server)
        assertEquals("ar", b.settings.settings.value.subtitleLanguage)
        assertEquals("ar", server.rows.values.single { it.kind == "settings" }.data!!.toString().substringAfter(":\"").take(2))
    }

    @Test
    fun `apres une synchronisation, rien a renvoyer`() = runBlocking<Unit> {
        a.library.add(bunny); a.changed()
        assertEquals(1, a.sync(server).pushed, "le titre ; les réglages par défaut ne sont pas envoyés")
        val second = a.sync(server)
        assertEquals(0, second.pushed)
        assertEquals(0, second.applied)
        assertEquals(0, a.engine.pendingChanges)
    }

    @Test
    fun `changement d utilisateur, rien n est herite du compte precedent`() = runBlocking<Unit> {
        a.library.add(bunny); a.changed(); a.sync(server)
        val other = FakeServer()
        a.engine.sync("u2", other)
        assertTrue(other.rows.containsKey("library" to "movie:tt1"), "le nouveau compte reçoit les données locales")
    }
}
