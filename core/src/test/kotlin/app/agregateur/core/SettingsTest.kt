package app.agregateur.core

import app.agregateur.core.settings.FileSettingsStore
import app.agregateur.core.settings.InMemorySettingsStore
import app.agregateur.core.settings.Settings
import app.agregateur.core.settings.SettingsRepository
import app.agregateur.core.settings.SettingsStore
import app.agregateur.core.settings.ThemeMode
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SettingsTest {
    @Test
    fun `valeurs par defaut`() = runBlocking<Unit> {
        val repository = SettingsRepository(InMemorySettingsStore())
        repository.load()
        assertEquals(
            Settings(subtitleLanguage = null, preferExternalPlayer = false, themeMode = ThemeMode.SYSTEM),
            repository.settings.value,
        )
    }

    @Test
    fun `modification enregistree puis relue apres redemarrage`() = runBlocking<Unit> {
        val file = File.createTempFile("settings", ".json").also { it.delete(); it.deleteOnExit() }
        val first = SettingsRepository(FileSettingsStore(file))
        assertTrue(
            first.update {
                it.copy(subtitleLanguage = "fr", preferExternalPlayer = true, themeMode = ThemeMode.DARK, appLanguage = "en")
            },
        )

        val second = SettingsRepository(FileSettingsStore(file))
        second.load()
        assertEquals(Settings("fr", true, themeMode = ThemeMode.DARK, appLanguage = "en"), second.settings.value)
    }

    @Test
    fun `echec d enregistrement laisse les reglages inchanges`() = runBlocking<Unit> {
        val failing = object : SettingsStore {
            override suspend fun load() = Settings()
            override suspend fun save(settings: Settings) = throw IOException("disque plein")
        }
        val repository = SettingsRepository(failing)
        assertFalse(repository.update { it.copy(subtitleLanguage = "en") })
        assertEquals(Settings(), repository.settings.value)
    }

    @Test
    fun `fichier corrompu remplace par les valeurs par defaut`() = runBlocking<Unit> {
        val file = File.createTempFile("settings", ".json").also { it.deleteOnExit() }
        file.writeText("pas du json")
        val repository = SettingsRepository(FileSettingsStore(file))
        repository.load()
        assertEquals(Settings(), repository.settings.value)
        assertTrue(File(file.path + ".bak").also { it.deleteOnExit() }.exists())
    }
}
