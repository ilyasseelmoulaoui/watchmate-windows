package app.agregateur

/** Référence d’un titre à ouvrir (catalogue / bibliothèque → fiche). */
data class TitleRef(
    val type: String,
    val id: String,
    val moduleUrl: String,
    val videoId: String? = null,
)
