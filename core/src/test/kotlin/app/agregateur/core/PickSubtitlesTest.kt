package app.agregateur.core

import app.agregateur.core.model.Subtitle
import app.agregateur.core.playback.languageName
import app.agregateur.core.playback.normalizeLanguage
import java.util.Locale
import app.agregateur.core.playback.pickSubtitles
import kotlin.test.Test
import kotlin.test.assertEquals

class PickSubtitlesTest {
    @Test
    fun `codes de langue ramenes a deux lettres`() {
        assertEquals("fr", normalizeLanguage("fre"))
        assertEquals("fr", normalizeLanguage("fra"))
        assertEquals("fr", normalizeLanguage("fr-FR"))
        assertEquals("pt", normalizeLanguage("pob"))
        assertEquals("en", normalizeLanguage("eng"))
        assertEquals("de", normalizeLanguage("ger"))
        assertEquals("xyz", normalizeLanguage("xyz"))
    }

    @Test
    fun `nom lisible de la langue`() {
        assertEquals("Français", languageName("fre", Locale.FRENCH))
        assertEquals("English", languageName("eng", Locale.ENGLISH))
        assertEquals("xyz", languageName("xyz", Locale.FRENCH))
    }

    @Test
    fun `langue preferee, anglais, puis une piste par langue, borne`() {
        val all = listOf("eng", "eng", "eng", "ara", "fre", "fre", "fre", "spa", "spa", "pob", "tur", "ita", "ger", "pol")
            .mapIndexed { i, lang -> Subtitle(id = "$i", url = "https://s/$i", lang = lang) }
        val picked = pickSubtitles(all, preferred = "fr", max = 8)
        assertEquals(listOf("fre", "fre", "eng", "eng", "ara", "spa", "pob", "tur"), picked.map { it.lang })
        assertEquals(listOf("eng", "eng", "ara", "fre", "spa", "pob"), pickSubtitles(all, null, max = 6).map { it.lang })
        assertEquals(emptyList(), pickSubtitles(emptyList(), "fr"))
    }
}
