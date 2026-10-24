package app.agregateur.core.sync

import app.agregateur.core.storage.JsonFile
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Une famille d'éléments synchronisés (bibliothèque, modules, réglages), vue comme des paires clé → JSON. */
interface SyncCollection {
    val kind: String

    fun snapshot(): Map<String, JsonElement>

    /**
     * Vrai pour une valeur jamais modifiée (réglages par défaut d'une nouvelle installation). Elle ne compte pas comme
     * un changement tant que l'appareil n'a jamais été synchronisé : sinon elle écraserait les réglages du compte.
     */
    fun isPristine(key: String, value: JsonElement): Boolean = false

    /** Applique des changements venus du serveur ; faux si l'enregistrement local échoue. */
    suspend fun apply(upserts: Map<String, JsonElement>, deletes: Set<String>): Boolean
}

/** Accès au serveur, déjà authentifié. */
interface SyncBackend {
    suspend fun pull(afterSeq: Long): List<RemoteItem>

    suspend fun push(items: List<RemoteItem>)
}

/**
 * État de synchronisation de l'appareil. `baseline` : empreinte de chaque élément tel que serveur et appareil l'ont
 * en commun (« kind|key » → empreinte, ABSENT pour un élément supprimé). `dirty` : éléments modifiés ici depuis, avec
 * l'heure de la modification ; c'est elle qui départage un conflit (la modification la plus récente l'emporte).
 */
@Serializable
data class SyncState(
    val userId: String? = null,
    val cursor: Long = 0,
    val baseline: Map<String, String> = emptyMap(),
    val dirty: Map<String, Long> = emptyMap(),
    val lastSyncAt: Long? = null,
)

interface SyncStateStore {
    suspend fun load(): SyncState
    suspend fun save(state: SyncState)
}

class InMemorySyncStateStore(var state: SyncState = SyncState()) : SyncStateStore {
    override suspend fun load() = state
    override suspend fun save(state: SyncState) {
        this.state = state
    }
}

class FileSyncStateStore(file: File) : SyncStateStore {
    private val json = JsonFile(file, SyncState.serializer()) { SyncState() }
    override suspend fun load() = json.load()
    override suspend fun save(state: SyncState) = json.save(state)
}

data class SyncReport(val pulled: Int, val applied: Int, val pushed: Int)

class SyncEngine(
    private val collections: List<SyncCollection>,
    private val store: SyncStateStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var state = SyncState()

    val lastSyncAt: Long? get() = state.lastSyncAt
    val pendingChanges: Int get() = state.dirty.size

    suspend fun load() = lock.withLock { state = runCatching { store.load() }.getOrDefault(SyncState()) }

    /** À appeler à chaque changement local : retient l'heure de la modification de chaque élément touché. */
    suspend fun noteLocalChanges() = lock.withLock {
        val updated = markDirty(state)
        if (updated != state) persist(updated)
    }

    /** Oublie tout lien avec le compte (déconnexion, suppression du compte) ; les données locales restent. */
    suspend fun reset() = lock.withLock { persist(SyncState()) }

    suspend fun sync(userId: String, backend: SyncBackend): SyncReport = lock.withLock {
        var current = if (state.userId == userId) state else SyncState(userId = userId, dirty = state.dirty)
        current = markDirty(current)
        val baseline = current.baseline.toMutableMap()
        val dirty = current.dirty.toMutableMap()
        var cursor = current.cursor

        // 1. Changements des autres appareils.
        val pulled = mutableListOf<RemoteItem>()
        do {
            val page = backend.pull(cursor)
            pulled += page
            if (page.isNotEmpty()) cursor = page.maxOf { it.seq }
        } while (page.size >= PAGE)
        val local = snapshots()
        var applied = 0
        pulled.groupBy { it.kind }.forEach { (kind, items) ->
            val collection = collections.firstOrNull { it.kind == kind } ?: return@forEach
            val upserts = mutableMapOf<String, JsonElement>()
            val deletes = mutableSetOf<String>()
            // Pour une même clé, seule la dernière écriture du serveur compte.
            items.associateBy { it.key }.values.forEach { remote ->
                val id = id(kind, remote.key)
                val localChange = dirty[id]
                if (localChange != null && localChange >= remote.updatedAt) return@forEach
                dirty.remove(id)
                baseline[id] = fingerprint(remote.data)
                if (fingerprint(local[id]) == baseline[id]) return@forEach
                if (remote.data == null) deletes += remote.key else upserts[remote.key] = remote.data
            }
            if (upserts.isNotEmpty() || deletes.isNotEmpty()) {
                if (!collection.apply(upserts, deletes)) throw java.io.IOException("Enregistrement local impossible")
                applied += upserts.size + deletes.size
            }
        }

        // 2. Changements de cet appareil.
        val after = snapshots()
        val outgoing = dirty.map { (id, at) ->
            val (kind, key) = split(id)
            RemoteItem(kind, key, after[id], at)
        }
        outgoing.chunked(PUSH_CHUNK).forEach { backend.push(it) }
        outgoing.forEach { item ->
            val id = id(item.kind, item.key)
            baseline[id] = fingerprint(item.data)
            dirty.remove(id)
        }

        persist(SyncState(userId, cursor, baseline, dirty, clock()))
        SyncReport(pulled.size, applied, outgoing.size)
    }

    private fun markDirty(from: SyncState): SyncState {
        val now = clock()
        val dirty = from.dirty.toMutableMap()
        val local = snapshots()
        val ids = local.keys + from.baseline.keys + from.dirty.keys
        ids.forEach { id ->
            val value = local[id]
            val pristine = value != null && id !in from.baseline && collectionOf(id)?.isPristine(split(id).second, value) == true
            val changed = !pristine && fingerprint(value) != (from.baseline[id] ?: ABSENT)
            when {
                changed && id !in dirty -> dirty[id] = now
                !changed -> dirty.remove(id)
            }
        }
        return if (dirty == from.dirty) from else from.copy(dirty = dirty)
    }

    private fun collectionOf(id: String) = collections.firstOrNull { it.kind == split(id).first }

    private fun snapshots(): Map<String, JsonElement> =
        collections.flatMap { c -> c.snapshot().map { (key, value) -> id(c.kind, key) to value } }.toMap()

    private suspend fun persist(updated: SyncState) {
        state = updated
        runCatching { store.save(updated) }
    }

    private companion object {
        const val PAGE = 500
        const val PUSH_CHUNK = 200
        const val ABSENT = "-"

        fun id(kind: String, key: String) = "$kind|$key"

        fun split(id: String): Pair<String, String> = id.substringBefore('|') to id.substringAfter('|')

        fun fingerprint(value: JsonElement?): String {
            if (value == null) return ABSENT
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray())
            return Base64.getEncoder().encodeToString(digest).take(22)
        }
    }
}
