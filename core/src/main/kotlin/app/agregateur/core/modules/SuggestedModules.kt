package app.agregateur.core.modules

enum class SuggestionGroup { CATALOGS, SUBTITLES, SOURCES }

/** Un module proposé à l'installation en un geste (P3). `description` est en français, affichée telle quelle. */
data class SuggestedModule(val name: String, val manifestUrl: String, val group: SuggestionGroup, val description: String)

/**
 * Modules publics de l'écosystème compatible, sans configuration préalable, vérifiés par `LiveAddonsCheck`.
 * Liste choisie à la main : catalogues et fiches, sous-titres, et uniquement des sources légales (domaine public,
 * liens vers les services officiels). Aucun module qui fournit des copies de contenus protégés (SPEC §2.3, O12).
 * Écartés après essai : TMDB (délais dépassés, catalogues en erreur), Radios (flux http refusés par D5),
 * YouTube (vidéos sans source : le client d'origine les lit avec son lecteur YouTube intégré).
 */
val SUGGESTED_MODULES: List<SuggestedModule> = listOf(
    SuggestedModule(
        "Cinemeta", "https://v3-cinemeta.strem.io/manifest.json", SuggestionGroup.CATALOGS,
        "Films et séries populaires, fiches complètes avec saisons et épisodes, recherche.",
    ),
    SuggestedModule(
        "Streaming Catalogs", "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/manifest.json",
        SuggestionGroup.CATALOGS,
        "Ce qui est disponible sur Netflix, Disney+, HBO Max, Prime Video, Apple TV+ (catalogues seulement).",
    ),
    SuggestedModule(
        "IMDB Catalogs", "https://1fe84bc728af-imdb-catalogs.baby-beamup.club/manifest.json", SuggestionGroup.CATALOGS,
        "Classements IMDb des films et séries, par genre.",
    ),
    SuggestedModule(
        "Anime Kitsu", "https://anime-kitsu.strem.fun/manifest.json", SuggestionGroup.CATALOGS,
        "Animés : catalogues, fiches et épisodes, recherche.",
    ),
    SuggestedModule(
        "OpenSubtitles", "https://opensubtitles-v3.strem.io/manifest.json", SuggestionGroup.SUBTITLES,
        "Sous-titres dans de nombreuses langues, ajoutés automatiquement à la lecture.",
    ),
    SuggestedModule(
        "Public Domain Movies", "https://caching.stremio.net/publicdomainmovies.now.sh/manifest.json", SuggestionGroup.SOURCES,
        "Films du domaine public (pair-à-pair).",
    ),
    SuggestedModule(
        "WatchHub", "https://watchhub.strem.io/manifest.json", SuggestionGroup.SOURCES,
        "Indique où regarder un titre légalement et ouvre le service concerné.",
    ),
)
