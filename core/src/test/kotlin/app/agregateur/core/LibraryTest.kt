package app.agregateur.core

import app.agregateur.core.library.FileLibraryStore
import app.agregateur.core.library.InMemoryLibraryStore
import app.agregateur.core.library.LibraryEntry
import app.agregateur.core.library.LibraryRepository
import app.agregateur.core.library.LibraryStore
import app.agregateur.core.library.ProgressPolicy
import app.agregateur.core.library.TitleSnapshot
import app.agregateur.core.library.continueWatching
import app.agregateur.core.library.find
import app.agregateur.core.library.resumePositionMs
import app.agregateur.core.library.savedEntries
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LibraryTest {
    private var now = 1_000L
    private fun repo(store: LibraryStore = InMemoryLibraryStore()) = LibraryRepository(store) { now }

    private val film = TitleSnapshot("movie", "m1", "Film", "https://p/1.jpg", "https://mod/manifest.json")
    private val serie = TitleSnapshot("series", "s1", "Série", null, "https://mod/manifest.json")
    private val minute = 60_000L

    @Test
    fun `r11 seuils de progression`() {
        assertEquals(ProgressPolicy.Update.IGNORE, ProgressPolicy.classify(4_999, 100 * minute))
        assertEquals(ProgressPolicy.Update.IN_PROGRESS, ProgressPolicy.classify(5_000, 100 * minute))
        assertEquals(ProgressPolicy.Update.IN_PROGRESS, ProgressPolicy.classify(94 * minute, 100 * minute))
        assertEquals(ProgressPolicy.Update.FINISHED, ProgressPolicy.classify(95 * minute, 100 * minute))
        assertEquals(ProgressPolicy.Update.IN_PROGRESS, ProgressPolicy.classify(50 * minute, 0))
        assertEquals(ProgressPolicy.Update.IN_PROGRESS, ProgressPolicy.classify(50 * minute, -1))
    }

    @Test
    fun `ajout et retrait`() = runBlocking<Unit> {
        val repository = repo()
        assertTrue(repository.add(film))
        val entry = repository.entries.value.find("movie", "m1")!!
        assertTrue(entry.saved)
        assertEquals(1_000L, entry.addedAt)
        assertEquals(listOf(entry), repository.entries.value.savedEntries())

        assertTrue(repository.remove("movie", "m1"))
        assertTrue(repository.entries.value.isEmpty())
    }

    @Test
    fun `ajouter deux fois garde la date d ajout et rafraichit le titre`() = runBlocking<Unit> {
        val repository = repo()
        repository.add(film)
        now = 5_000L
        repository.add(film.copy(name = "Film (nouveau nom)", poster = null))
        val entry = repository.entries.value.single()
        assertEquals(1_000L, entry.addedAt)
        assertEquals("Film (nouveau nom)", entry.name)
        assertEquals("https://p/1.jpg", entry.poster)
    }

    @Test
    fun `bibliotheque triee du plus recent au plus ancien`() = runBlocking<Unit> {
        val repository = repo()
        repository.add(film)
        now = 2_000L
        repository.add(serie)
        assertEquals(listOf("s1", "m1"), repository.entries.value.savedEntries().map { it.id })
    }

    @Test
    fun `une lecture arretee cree une entree reprendre sans l ajouter a la bibliotheque`() = runBlocking<Unit> {
        val repository = repo()
        assertTrue(repository.recordProgress(film, "m1", 10 * minute, 100 * minute))
        val entry = repository.entries.value.single()
        assertFalse(entry.saved)
        assertEquals(listOf(entry), repository.entries.value.continueWatching())
        assertTrue(repository.entries.value.savedEntries().isEmpty())
        assertEquals(0.1f, entry.progress!!.ratio, 0.001f)
    }

    @Test
    fun `moins de 5 secondes est ignore`() = runBlocking<Unit> {
        val repository = repo()
        repository.recordProgress(film, "m1", 3_000, 100 * minute)
        assertTrue(repository.entries.value.isEmpty())
    }

    @Test
    fun `fin de lecture retire de reprendre mais garde la bibliotheque`() = runBlocking<Unit> {
        val repository = repo()
        repository.add(film)
        repository.recordProgress(film, "m1", 10 * minute, 100 * minute)
        repository.recordProgress(film, "m1", 96 * minute, 100 * minute)
        val entry = repository.entries.value.single()
        assertTrue(entry.saved)
        assertNull(entry.progress)
        assertTrue(repository.entries.value.continueWatching().isEmpty())
    }

    @Test
    fun `retirer de reprendre sans finir garde la bibliotheque`() = runBlocking<Unit> {
        val repository = repo()
        repository.add(film)
        repository.recordProgress(film, "m1", 10 * minute, 100 * minute)
        assertTrue(repository.clearProgress("movie", "m1"))
        val entry = repository.entries.value.single()
        assertTrue(entry.saved)
        assertNull(entry.progress)
        assertTrue(repository.entries.value.continueWatching().isEmpty())
    }

    @Test
    fun `fin de lecture d un titre non ajoute supprime l entree`() = runBlocking<Unit> {
        val repository = repo()
        repository.recordProgress(film, "m1", 10 * minute, 100 * minute)
        repository.recordProgress(film, "m1", 99 * minute, 100 * minute)
        assertTrue(repository.entries.value.isEmpty())
    }

    @Test
    fun `retirer de la bibliotheque garde reprendre tant qu il y a une progression`() = runBlocking<Unit> {
        val repository = repo()
        repository.add(film)
        repository.recordProgress(film, "m1", 10 * minute, 100 * minute)
        repository.remove("movie", "m1")
        val entry = repository.entries.value.single()
        assertFalse(entry.saved)
        assertNull(entry.addedAt)
        assertTrue(entry.progress != null)

        assertTrue(repository.clearProgress("movie", "m1"))
        assertTrue(repository.entries.value.isEmpty())
    }

    @Test
    fun `reprendre trie par derniere lecture`() = runBlocking<Unit> {
        val repository = repo()
        repository.recordProgress(film, "m1", 10 * minute, 100 * minute)
        now = 9_000L
        repository.recordProgress(serie, "s1:1:2", 7 * minute, 40 * minute)
        assertEquals(listOf("s1", "m1"), repository.entries.value.continueWatching().map { it.id })
    }

    @Test
    fun `position de reprise seulement pour la meme video`() = runBlocking<Unit> {
        val repository = repo()
        repository.recordProgress(serie, "s1:1:2", 7 * minute, 40 * minute)
        val entry = repository.entries.value.find("series", "s1")
        assertEquals(7 * minute, entry.resumePositionMs("s1:1:2"))
        assertEquals(0L, entry.resumePositionMs("s1:1:3"))
        assertEquals(0L, null.resumePositionMs("s1:1:2"))
    }

    @Test
    fun `une progression plus recente remplace l ancienne`() = runBlocking<Unit> {
        val repository = repo()
        repository.recordProgress(serie, "s1:1:1", 7 * minute, 40 * minute)
        now = 3_000L
        repository.recordProgress(serie, "s1:1:2", 2 * minute, 40 * minute)
        val progress = repository.entries.value.single().progress!!
        assertEquals("s1:1:2", progress.videoId)
        assertEquals(3_000L, progress.updatedAt)
    }

    @Test
    fun `echec d enregistrement laisse l etat inchange`() = runBlocking<Unit> {
        val failing = object : LibraryStore {
            override suspend fun load() = emptyList<LibraryEntry>()
            override suspend fun save(entries: List<LibraryEntry>) = throw IOException("disque plein")
        }
        val repository = repo(failing)
        assertFalse(repository.add(film))
        assertFalse(repository.recordProgress(film, "m1", 10 * minute, 100 * minute))
        assertTrue(repository.entries.value.isEmpty())
    }

    @Test
    fun `ajouter avant le chargement n ecrase pas la bibliotheque enregistree`() = runBlocking<Unit> {
        val existing = LibraryEntry("movie", "old", "Ancien", null, "https://mod/manifest.json", saved = true, addedAt = 1)
        val store = InMemoryLibraryStore(listOf(existing))
        val repository = repo(store)
        repository.add(film)
        assertEquals(2, store.load().size)
        assertEquals(existing, store.load().first())
    }

    @Test
    fun `le fichier survit a un redemarrage`() = runBlocking<Unit> {
        val file = File.createTempFile("library", ".json").also { it.delete(); it.deleteOnExit() }
        val first = repo(FileLibraryStore(file))
        first.add(film)
        first.recordProgress(serie, "s1:1:1", 10 * minute, 40 * minute)

        val second = repo(FileLibraryStore(file))
        second.load()
        assertEquals(first.entries.value, second.entries.value)
        assertEquals(2, second.entries.value.size)
    }

    @Test
    fun `fichier corrompu mis de cote`() = runBlocking<Unit> {
        val file = File.createTempFile("library", ".json").also { it.deleteOnExit() }
        file.writeText("{ pas du json")
        val repository = repo(FileLibraryStore(file))
        repository.load()
        assertTrue(repository.entries.value.isEmpty())
        assertTrue(File(file.path + ".bak").also { it.deleteOnExit() }.exists())
    }

    @Test
    fun `effacer l historique garde la bibliotheque`() = runBlocking<Unit> {
        val repository = repo()
        repository.add(film)
        repository.recordProgress(film, "m1", 10 * minute, 100 * minute)
        repository.recordProgress(serie, "s1:1:1", 10 * minute, 40 * minute)
        assertTrue(repository.clearAllProgress())
        assertEquals(listOf("m1"), repository.entries.value.map { it.id })
        assertTrue(repository.entries.value.continueWatching().isEmpty())
    }
}
