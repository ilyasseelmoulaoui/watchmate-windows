package app.agregateur.core.modules

import app.agregateur.core.storage.saveThenPublish
import app.agregateur.core.addon.AddonClient
import app.agregateur.core.addon.AddonError
import app.agregateur.core.addon.AddonResult
import app.agregateur.core.addon.AddonUrls
import app.agregateur.core.model.Manifest
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
enum class Origin { DEFAULT, LIST, ADDRESS, ANNOUNCEMENT }

@Serializable
data class InstalledModule(
    val manifestUrl: String,
    val manifest: Manifest,
    val origin: Origin,
    val installedAt: Long,
)

sealed interface InstallError {
    data object EmptyAddress : InstallError
    data class Addon(val error: AddonError) : InstallError
    data object Storage : InstallError
}

sealed interface InstallResult {
    data class Installed(val module: InstalledModule) : InstallResult
    data class AlreadyInstalled(val module: InstalledModule) : InstallResult
    data class Failed(val error: InstallError) : InstallResult
}

interface ModuleStore {
    @Throws(IOException::class)
    suspend fun load(): List<InstalledModule>

    @Throws(IOException::class)
    suspend fun save(modules: List<InstalledModule>)
}

class InMemoryModuleStore(private var modules: List<InstalledModule> = emptyList()) : ModuleStore {
    override suspend fun load() = modules
    override suspend fun save(modules: List<InstalledModule>) {
        this.modules = modules
    }
}

/**
 * Installation et retrait de modules.
 * R1 : l'adresse est nettoyée, refusée si vide. R2 : un module n'apparaît dans la liste
 * qu'après la lecture réussie de son manifeste et l'enregistrement ; sinon rien n'est installé.
 */
class ModuleRepository(
    private val client: AddonClient,
    private val store: ModuleStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow<List<InstalledModule>>(emptyList())
    private val lock = Mutex()

    private var loaded = false

    val modules: StateFlow<List<InstalledModule>> = state.asStateFlow()

    suspend fun load() = lock.withLock { loadLocked() }

    /** À appeler sous le verrou : évite qu'une installation avant le chargement n'écrase la liste enregistrée. */
    private suspend fun loadLocked() {
        state.value = try {
            store.load()
        } catch (_: IOException) {
            emptyList()
        }
        loaded = true
    }

    suspend fun install(address: String, origin: Origin = Origin.ADDRESS): InstallResult {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return InstallResult.Failed(InstallError.EmptyAddress)
        val base = AddonUrls.base(trimmed)
            ?: return InstallResult.Failed(InstallError.Addon(AddonError.InvalidUrl))
        val manifestUrl = AddonUrls.manifest(base).toString()

        state.value.firstOrNull { it.manifestUrl == manifestUrl }
            ?.let { return InstallResult.AlreadyInstalled(it) }

        val manifest: Manifest = when (val result = client.manifest(manifestUrl)) {
            is AddonResult.Failure -> return InstallResult.Failed(InstallError.Addon(result.error))
            is AddonResult.Success -> result.value
        }

        return lock.withLock {
            if (!loaded) loadLocked()
            state.value.firstOrNull { it.manifestUrl == manifestUrl }
                ?.let { return@withLock InstallResult.AlreadyInstalled(it) }
            val module = InstalledModule(manifestUrl, manifest, origin, clock())
            val updated = state.value + module
            if (!saveThenPublish { store.save(updated); state.value = updated }) {
                return@withLock InstallResult.Failed(InstallError.Storage)
            }
            InstallResult.Installed(module)
        }
    }

    /**
     * Changements venus d'un autre appareil. Le manifeste synchronisé est repris tel quel : il a été lu et validé
     * à l'installation sur l'appareil d'origine, et seules ses adresses http(s) sont jamais utilisées (D7).
     */
    suspend fun applyRemote(upserts: List<InstalledModule>, deletes: Set<String>): Boolean = lock.withLock {
        if (!loaded) loadLocked()
        val incoming = upserts.filter { AddonUrls.base(it.manifestUrl) != null }.associateBy { it.manifestUrl }
        val updated = state.value.filterNot { it.manifestUrl in deletes || it.manifestUrl in incoming } + incoming.values
        if (updated == state.value) return@withLock true
        saveThenPublish { store.save(updated); state.value = updated }
    }

    /** Retire un module ; renvoie faux si l'enregistrement échoue (la liste reste inchangée). */
    suspend fun remove(manifestUrl: String): Boolean = lock.withLock {
        if (!loaded) loadLocked()
        val remaining = state.value.filterNot { it.manifestUrl == manifestUrl }
        saveThenPublish { store.save(remaining); state.value = remaining }
    }
}
