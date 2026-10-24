package app.agregateur.core.playback

import app.agregateur.core.model.Subtitle
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class SubtitleFormat { SUBRIP, WEBVTT, SSA, TTML }

/** Format déduit de l'extension du fichier ; SubRip par défaut, le plus courant. */
fun subtitleFormatOf(url: String): SubtitleFormat {
    val path = (url.toHttpUrlOrNull()?.encodedPath ?: url.substringBefore('?')).lowercase()
    return when {
        path.endsWith(".vtt") -> SubtitleFormat.WEBVTT
        path.endsWith(".ass") || path.endsWith(".ssa") -> SubtitleFormat.SSA
        path.endsWith(".ttml") || path.endsWith(".dfxp") || path.endsWith(".xml") -> SubtitleFormat.TTML
        else -> SubtitleFormat.SUBRIP
    }
}

/** Codes ISO 639-2 « bibliographiques » (et `pob`, portugais du Brésil) que `Locale` ne connaît pas. */
private val bibliographic = mapOf(
    "fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "per" to "fa",
    "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka",
    "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy", "pob" to "pt",
)

private val threeLetter: Map<String, String> by lazy {
    Locale.getISOLanguages().associateBy { runCatching { Locale(it).isO3Language }.getOrDefault(it) }
}

/** Code de langue réduit à deux lettres quand c'est possible (`fre`, `fra`, `fr-FR` → `fr`). */
fun normalizeLanguage(code: String): String {
    val base = code.trim().lowercase().substringBefore('-').substringBefore('_')
    return when (base.length) {
        3 -> bibliographic[base] ?: threeLetter[base] ?: base
        else -> base
    }
}

/** Nom de la langue dans la langue de l'appareil (« fre » → « Français ») ; le code tel quel s'il est inconnu. */
fun languageName(code: String, display: Locale = Locale.getDefault()): String {
    val normalized = normalizeLanguage(code)
    val name = Locale(normalized).getDisplayLanguage(display)
    return if (name.isBlank() || name.equals(normalized, ignoreCase = true)) code
    else name.replaceFirstChar { it.titlecase(display) }
}

/**
 * Sous-titres confiés au lecteur. Un module peut en proposer près d'une centaine ; le lecteur les télécharge tous au
 * démarrage et le serveur finit par refuser (429). On garde la langue préférée, l'anglais, puis une piste par
 * autre langue dans l'ordre reçu (ceux de la source d'abord), dans la limite de [max].
 */
fun pickSubtitles(all: List<Subtitle>, preferred: String?, max: Int = 8, perLanguage: Int = 2): List<Subtitle> {
    val wanted = preferred?.let(::normalizeLanguage)
    val counts = mutableMapOf<String, Int>()
    val picked = LinkedHashSet<Subtitle>()
    fun take(candidates: List<Subtitle>, limitPerLanguage: Int) {
        for (s in candidates) {
            if (picked.size >= max) return
            val lang = normalizeLanguage(s.lang)
            if (s in picked || counts.getOrDefault(lang, 0) >= limitPerLanguage) continue
            picked += s
            counts[lang] = counts.getOrDefault(lang, 0) + 1
        }
    }
    if (wanted != null) take(all.filter { normalizeLanguage(it.lang) == wanted }, perLanguage)
    take(all.filter { normalizeLanguage(it.lang) == "en" }, perLanguage)
    take(all, 1)
    return picked.toList()
}
