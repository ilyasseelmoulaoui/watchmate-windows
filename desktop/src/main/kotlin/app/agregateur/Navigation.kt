package app.agregateur

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import app.agregateur.player.PlayRequest
import app.agregateur.ui.theme.WmIcons

enum class Tab(val labelFr: String, val labelEn: String, val icon: ImageVector) {
    Home("Accueil", "Home", WmIcons.Home),
    Explore("Explorer", "Explore", WmIcons.Explore),
    Library("Bibliothèque", "Library", WmIcons.Library),
    Modules("Modules", "Modules", WmIcons.Modules),
    Settings("Réglages", "Settings", WmIcons.Settings),
}

/** Onglet courant, plus deux écrans secondaires empilés : résultats de recherche, puis fiche titre. */
@Stable
class AppNav {
    var tab by mutableStateOf(Tab.Home)
        private set
    var search by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf<TitleRef?>(null)
        private set

    /** Lecture en cours : le lecteur remplace le rail et le contenu tant qu'elle dure. */
    var playing by mutableStateOf<PlayRequest?>(null)
        private set

    /** Panneau « Regarder ensemble » ouvert à droite (hors lecture). */
    var partyOpen by mutableStateOf(false)

    val searchFocus = FocusRequester()

    val canGoBack: Boolean get() = playing != null || title != null || search != null

    fun play(request: PlayRequest) {
        playing = request
        partyOpen = false
    }

    fun stopPlaying() {
        playing = null
    }

    fun select(tab: Tab) {
        this.tab = tab
        search = null
        title = null
    }

    fun open(ref: TitleRef) {
        title = ref
    }

    fun search(query: String) {
        search = query
        title = null
    }

    fun back(): Boolean = when {
        playing != null -> {
            playing = null
            true
        }
        title != null -> {
            title = null
            true
        }
        search != null -> {
            search = null
            true
        }
        else -> false
    }

    fun focusSearch() {
        runCatching { searchFocus.requestFocus() }
    }
}
