package app.agregateur.core.sync

import app.agregateur.core.library.LibraryEntry
import app.agregateur.core.library.LibraryRepository
import app.agregateur.core.modules.InstalledModule
import app.agregateur.core.modules.ModuleRepository
import app.agregateur.core.settings.SettingsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val SyncJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** Titres de la bibliothèque et progression de lecture ; clé : « type:id ». */
class LibraryCollection(private val repository: LibraryRepository) : SyncCollection {
    override val kind = "library"

    override fun snapshot(): Map<String, JsonElement> =
        repository.entries.value.associate { it.key to SyncJson.encodeToJsonElement(LibraryEntry.serializer(), it) }

    override suspend fun apply(upserts: Map<String, JsonElement>, deletes: Set<String>): Boolean = repository.applyRemote(
        upserts.values.mapNotNull { runCatching { SyncJson.decodeFromJsonElement(LibraryEntry.serializer(), it) }.getOrNull() },
        deletes,
    )
}

/** Modules installés ; clé : adresse du manifeste. */
class ModuleCollection(private val repository: ModuleRepository) : SyncCollection {
    override val kind = "module"

    override fun snapshot(): Map<String, JsonElement> =
        repository.modules.value.associate { it.manifestUrl to SyncJson.encodeToJsonElement(InstalledModule.serializer(), it) }

    override suspend fun apply(upserts: Map<String, JsonElement>, deletes: Set<String>): Boolean = repository.applyRemote(
        upserts.values.mapNotNull { runCatching { SyncJson.decodeFromJsonElement(InstalledModule.serializer(), it) }.getOrNull() },
        deletes,
    )
}

/**
 * Réglages partagés entre appareils. L'accord pour la lecture pair-à-pair reste propre à chaque appareil :
 * l'avertissement doit être lu là où la lecture a lieu.
 */
class SettingsCollection(private val repository: SettingsRepository) : SyncCollection {
    override val kind = "settings"

    @Serializable
    private data class Shared(val subtitleLanguage: String? = null, val preferExternalPlayer: Boolean = false)

    override fun snapshot(): Map<String, JsonElement> {
        val s = repository.settings.value
        return mapOf(KEY to SyncJson.encodeToJsonElement(Shared.serializer(), Shared(s.subtitleLanguage, s.preferExternalPlayer)))
    }

    override fun isPristine(key: String, value: JsonElement): Boolean =
        value == SyncJson.encodeToJsonElement(Shared.serializer(), Shared())

    override suspend fun apply(upserts: Map<String, JsonElement>, deletes: Set<String>): Boolean {
        val shared = upserts[KEY]?.let { runCatching { SyncJson.decodeFromJsonElement(Shared.serializer(), it) }.getOrNull() }
            ?: return true
        return repository.update { it.copy(subtitleLanguage = shared.subtitleLanguage, preferExternalPlayer = shared.preferExternalPlayer) }
    }

    private companion object {
        const val KEY = "settings"
    }
}
