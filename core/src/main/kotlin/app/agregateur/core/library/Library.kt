package app.agregateur.core.library

import app.agregateur.core.storage.saveThenPublish
import app.agregateur.core.storage.jsonListFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Un titre connu de l'application : ajouté par l'utilisateur (`saved`) et/ou en cours de lecture (`progress`). */
@Serializable
data class LibraryEntry(
    val type: String,
    val id: String,
    val name: String,
    val poster: String? = null,
    val moduleUrl: String,
    val saved: Boolean = false,
    val addedAt: Long? = null,
    val progress: Progress? = null,
) {
    val key: String get() = "$type:$id"
}

@Serializable
data class Progress(val videoId: String, val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
    val ratio: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Ce qu'il faut savoir d'un titre pour le garder, sans dépendre d'un module. */
data class TitleSnapshot(val type: String, val id: String, val name: String, val poster: String?, val moduleUrl: String)

/** R11 : un titre figure dans « reprendre » dès qu'une lecture s'arrête avant la fin, et en sort une fois terminé. */
object ProgressPolicy {
    const val MIN_POSITION_MS = 5_000L
    const val FINISHED_RATIO = 0.95

    enum class Update { IGNORE, IN_PROGRESS, FINISHED }

    /** Durée inconnue (0 ou négative) : on garde la position, on ne peut pas conclure à la fin. */
    fun classify(positionMs: Long, durationMs: Long): Update = when {
        positionMs < MIN_POSITION_MS -> Update.IGNORE
        durationMs > 0 && positionMs >= durationMs * FINISHED_RATIO -> Update.FINISHED
        else -> Update.IN_PROGRESS
    }
}

fun List<LibraryEntry>.savedEntries(): List<LibraryEntry> =
    filter { it.saved }.sortedByDescending { it.addedAt ?: 0L }

fun List<LibraryEntry>.continueWatching(): List<LibraryEntry> =
    filter { it.progress != null }.sortedByDescending { it.progress?.updatedAt ?: 0L }

fun List<LibraryEntry>.find(type: String, id: String): LibraryEntry? = firstOrNull { it.type == type && it.id == id }

/** Position de reprise : seulement si la progression enregistrée concerne la même vidéo. */
fun LibraryEntry?.resumePositionMs(videoId: String): Long =
    this?.progress?.takeIf { it.videoId == videoId }?.positionMs ?: 0L

interface LibraryStore {
    @Throws(IOException::class)
    suspend fun load(): List<LibraryEntry>

    @Throws(IOException::class)
    suspend fun save(entries: List<LibraryEntry>)
}

class InMemoryLibraryStore(private var entries: List<LibraryEntry> = emptyList()) : LibraryStore {
    override suspend fun load() = entries
    override suspend fun save(entries: List<LibraryEntry>) {
        this.entries = entries
    }
}

class FileLibraryStore(file: File) : LibraryStore {
    private val json = jsonListFile(file, LibraryEntry.serializer())
    override suspend fun load(): List<LibraryEntry> = json.load()
    override suspend fun save(entries: List<LibraryEntry>) = json.save(entries)
}

/**
 * Bibliothèque et progression. Chaque opération est enregistrée avant d'être visible ;
 * si l'enregistrement échoue, l'état reste inchangé et l'opération renvoie faux (R12).
 */
class LibraryRepository(
    private val store: LibraryStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow<List<LibraryEntry>>(emptyList())
    private val lock = Mutex()
    private var loaded = false

    val entries: StateFlow<List<LibraryEntry>> = state.asStateFlow()

    suspend fun load() = lock.withLock { loadLocked() }

    private suspend fun loadLocked() {
        state.value = try {
            store.load()
        } catch (_: IOException) {
            emptyList()
        }
        loaded = true
    }

    suspend fun add(title: TitleSnapshot): Boolean = mutate { list ->
        val existing = list.find(title.type, title.id)
        val entry = (existing ?: title.toEntry()).copy(
            name = title.name.ifBlank { existing?.name ?: title.id },
            poster = title.poster ?: existing?.poster,
            moduleUrl = title.moduleUrl,
            saved = true,
            addedAt = existing?.addedAt?.takeIf { existing.saved } ?: clock(),
        )
        list.replacing(entry)
    }

    /** Retire de la bibliothèque ; si le titre est en cours de lecture, il reste dans « reprendre ». */
    suspend fun remove(type: String, id: String): Boolean = mutate { list ->
        val existing = list.find(type, id) ?: return@mutate list
        list.replacing(existing.copy(saved = false, addedAt = null))
    }

    suspend fun clearProgress(type: String, id: String): Boolean = mutate { list ->
        val existing = list.find(type, id) ?: return@mutate list
        list.replacing(existing.copy(progress = null))
    }

    /** Efface toutes les progressions ; les titres ajoutés à la bibliothèque restent. */
    suspend fun clearAllProgress(): Boolean = mutate { list -> list.map { it.copy(progress = null) } }

    suspend fun recordProgress(title: TitleSnapshot, videoId: String, positionMs: Long, durationMs: Long): Boolean =
        mutate { list ->
            val existing = list.find(title.type, title.id)
            when (ProgressPolicy.classify(positionMs, durationMs)) {
                ProgressPolicy.Update.IGNORE -> list
                ProgressPolicy.Update.FINISHED -> existing?.let { list.replacing(it.copy(progress = null)) } ?: list
                ProgressPolicy.Update.IN_PROGRESS -> {
                    val base = existing ?: title.toEntry()
                    list.replacing(
                        base.copy(
                            name = title.name.ifBlank { base.name },
                            poster = title.poster ?: base.poster,
                            moduleUrl = title.moduleUrl,
                            progress = Progress(videoId, positionMs, durationMs.coerceAtLeast(0L), clock()),
                        ),
                    )
                }
            }
        }

    /** Changements venus d'un autre appareil : titres remplacés tels quels, titres retirés par leur clé « type:id ». */
    suspend fun applyRemote(upserts: List<LibraryEntry>, deletes: Set<String>): Boolean = mutate { list ->
        val incoming = upserts.associateBy { it.key }
        list.filterNot { it.key in deletes || it.key in incoming } + incoming.values
    }

    private suspend fun mutate(change: (List<LibraryEntry>) -> List<LibraryEntry>): Boolean = lock.withLock {
        if (!loaded) loadLocked()
        val updated = change(state.value).filter { it.saved || it.progress != null }
        if (updated == state.value) return@withLock true
        saveThenPublish { store.save(updated); state.value = updated }
    }

    private fun TitleSnapshot.toEntry() = LibraryEntry(type, id, name.ifBlank { id }, poster, moduleUrl)

    private fun List<LibraryEntry>.replacing(entry: LibraryEntry): List<LibraryEntry> =
        if (any { it.key == entry.key }) map { if (it.key == entry.key) entry else it } else this + entry
}
