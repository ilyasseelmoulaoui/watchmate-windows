package app.agregateur.core

import app.agregateur.core.addon.AddonUrls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AddonUrlsTest {
    @Test
    fun `retire manifest json et les espaces`() {
        val base = assertNotNull(AddonUrls.base("  https://exemple.org/mod/manifest.json  "))
        assertEquals("https://exemple.org/mod/", base.toString())
        assertEquals("https://exemple.org/mod/manifest.json", AddonUrls.manifest(base).toString())
    }

    @Test
    fun `lien d installation stremio lu comme https`() {
        val base = assertNotNull(AddonUrls.base("stremio://v3-cinemeta.strem.io/manifest.json"))
        assertEquals("https://v3-cinemeta.strem.io/manifest.json", AddonUrls.manifest(base).toString())
        assertEquals("https://x.org/a/b/", AddonUrls.base("STREMIO://x.org/a/b/manifest.json").toString())
    }

    @Test
    fun `adresse invalide ou non http est refusee`() {
        assertNull(AddonUrls.base(""))
        assertNull(AddonUrls.base("pas une adresse"))
        assertNull(AddonUrls.base("ftp://exemple.org/manifest.json"))
    }

    @Test
    fun `ressource sans extra`() {
        val base = assertNotNull(AddonUrls.base("https://exemple.org"))
        assertEquals(
            "https://exemple.org/stream/series/tt0898266:9:17.json",
            AddonUrls.resource(base, "stream", "series", "tt0898266:9:17").toString(),
        )
    }

    @Test
    fun `ressource avec extra encode en requete`() {
        val base = assertNotNull(AddonUrls.base("https://exemple.org"))
        val url = AddonUrls.resource(
            base, "catalog", "movie", "top",
            linkedMapOf("search" to "game of thrones", "skip" to "100"),
        )
        assertEquals(
            "https://exemple.org/catalog/movie/top/search=game%20of%20thrones&skip=100.json",
            url.toString(),
        )
    }
}
