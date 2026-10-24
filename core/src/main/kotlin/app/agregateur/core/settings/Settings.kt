package app.agregateur.core.settings

import app.agregateur.core.playback.SubtitleStyle
import app.agregateur.core.storage.saveThenPublish
import app.agregateur.core.storage.JsonFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Apparence : suit le système, ou force le clair / le sombre. Propre à chaque appareil (non synchronisé). */
@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Réglages de l'appareil (profil unique au MVP).
 * `subtitleLanguage` : code de langue ISO 639-1 (« fr ») choisi automatiquement à la lecture ; null = aucun.
 * `p2pNoticeAccepted` : l'avertissement sur la lecture pair-à-pair a été lu et accepté.
 * `themeMode` : clair / sombre / système ; reste local (comme l'accord pair-à-pair).
 * `appLanguage` : code ISO 639-1 de l'interface (`fr`, `en`) ; null = langue du système. Local à l'appareil.
 */
@Serializable
data class Settings(
    val subtitleLanguage: String? = null,
    val preferExternalPlayer: Boolean = false,
    val p2pNoticeAccepted: Boolean = false,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appLanguage: String? = null,
)

/** Langues de l'interface proposées dans les Réglages. Noms dans leur propre langue. */
val APP_LANGUAGES: List<Pair<String, String>> = listOf(
    "fr" to "Français",
    "en" to "English",
)

/** Langues proposées pour les sous-titres. Les noms sont écrits dans leur propre langue. */
val SUBTITLE_LANGUAGES: List<Pair<String, String>> = listOf(
    "fr" to "Français",
    "en" to "English",
    "es" to "Español",
    "de" to "Deutsch",
    "it" to "Italiano",
    "pt" to "Português",
    "nl" to "Nederlands",
    "ar" to "العربية",
    "tr" to "Türkçe",
)

interface SettingsStore {
    @Throws(IOException::class)
    suspend fun load(): Settings

    @Throws(IOException::class)
    suspend fun save(settings: Settings)
}

class InMemorySettingsStore(private var settings: Settings = Settings()) : SettingsStore {
    override suspend fun load() = settings
    override suspend fun save(settings: Settings) {
        this.settings = settings
    }
}

class FileSettingsStore(file: File) : SettingsStore {
    private val json = JsonFile(file, Settings.serializer()) { Settings() }
    override suspend fun load(): Settings = json.load()
    override suspend fun save(settings: Settings) = json.save(settings)
}

/** Une modification n'est visible qu'une fois enregistrée ; en cas d'échec, elle renvoie faux. */
class SettingsRepository(private val store: SettingsStore) {
    private val state = MutableStateFlow(Settings())
    private val lock = Mutex()
    private var loaded = false

    val settings: StateFlow<Settings> = state.asStateFlow()

    suspend fun load() = lock.withLock { loadLocked() }

    private suspend fun loadLocked() {
        state.value = try {
            store.load()
        } catch (_: IOException) {
            Settings()
        }
        loaded = true
    }

    suspend fun update(change: (Settings) -> Settings): Boolean = lock.withLock {
        if (!loaded) loadLocked()
        val updated = change(state.value)
        if (updated == state.value) return@withLock true
        saveThenPublish { store.save(updated); state.value = updated }
    }
}
