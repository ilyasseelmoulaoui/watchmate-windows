package app.agregateur.core

import app.agregateur.core.addon.AddonJson
import app.agregateur.core.addon.supports
import app.agregateur.core.model.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestTest {
    private val json = """
        {
          "id": "org.exemple.mod", "name": "Exemple", "version": "1.0.0", "description": "d",
          "types": ["movie", "series"],
          "idPrefixes": ["tt"],
          "resources": [
            "catalog",
            "stream",
            { "name": "meta", "types": ["movie"], "idPrefixes": ["kitsu:"] },
            { "name": "subtitles", "types": ["series"] }
          ],
          "catalogs": [
            { "type": "movie", "id": "top", "name": "Top",
              "extra": [ { "name": "genre", "options": ["a", "b"] }, { "name": "skip" } ] }
          ],
          "champInconnu": 1
        }
    """.trimIndent()

    private val manifest = AddonJson.decodeFromString<Manifest>(json)

    @Test
    fun `lit ressources texte et objet`() {
        assertEquals(listOf("catalog", "stream", "meta", "subtitles"), manifest.resources.map { it.name })
        assertTrue(manifest.resources[0].inherits)
        assertFalse(manifest.resources[2].inherits)
        assertEquals("top", manifest.catalogs.single().id)
        assertEquals(2, manifest.catalogs.single().extra.size)
    }

    @Test
    fun `ressource texte herite des types et prefixes`() {
        assertTrue(manifest.supports("stream", "series", "tt0898266:9:17"))
        assertFalse(manifest.supports("stream", "series", "kitsu:1"))
        assertFalse(manifest.supports("stream", "tv", "tt1"))
    }

    @Test
    fun `ressource objet utilise ses propres types et prefixes`() {
        assertTrue(manifest.supports("meta", "movie", "kitsu:1"))
        assertFalse(manifest.supports("meta", "movie", "tt1"))
        assertFalse(manifest.supports("meta", "series", "kitsu:1"))
    }

    @Test
    fun `objet sans prefixes accepte tous les identifiants`() {
        assertTrue(manifest.supports("subtitles", "series", "n_importe_quoi"))
    }

    @Test
    fun `ressource non declaree n est pas interrogee`() {
        assertFalse(manifest.supports("addon_catalog", "movie"))
    }

    @Test
    fun `les prefixes ne filtrent pas les catalogues`() {
        assertTrue(manifest.supports("catalog", "movie", "id_sans_prefixe"))
    }
}
