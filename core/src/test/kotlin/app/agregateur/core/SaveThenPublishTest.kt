package app.agregateur.core

import app.agregateur.core.settings.Settings
import app.agregateur.core.settings.SettingsRepository
import app.agregateur.core.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SaveThenPublishTest {
    /** Écriture lente, sur un autre fil, comme le vrai fichier. */
    private class SlowStore : SettingsStore {
        var saved: Settings? = null
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun load() = Settings()
        override suspend fun save(settings: Settings) = withContext(Dispatchers.IO) {
            started.complete(Unit)
            release.await()
            saved = settings
        }
    }

    @Test
    fun `appelant annule pendant l ecriture, fichier et memoire restent d accord`() = runBlocking<Unit> {
        val store = SlowStore()
        val repository = SettingsRepository(store)
        repository.load()
        val job = launch { repository.update { it.copy(p2pNoticeAccepted = true) } }
        store.started.await()
        job.cancel()
        store.release.complete(Unit)
        job.join()
        assertEquals(true, store.saved?.p2pNoticeAccepted)
        assertTrue(repository.settings.value.p2pNoticeAccepted)
    }
}
