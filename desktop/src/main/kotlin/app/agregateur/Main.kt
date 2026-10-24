package app.agregateur

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.agregateur.core.settings.ThemeMode
import app.agregateur.screens.HomeScreen
import app.agregateur.screens.LibraryScreen
import app.agregateur.screens.ModulesScreen
import app.agregateur.screens.PlaceholderScreen
import app.agregateur.screens.SettingsScreen
import java.util.Locale

enum class Tab(val labelFr: String, val labelEn: String) {
    Home("Accueil", "Home"),
    Explore("Explorer", "Explore"),
    Library("Bibliothèque", "Library"),
    Modules("Modules", "Modules"),
    Settings("Réglages", "Settings"),
}

fun main() = application {
    val app = remember {
        DesktopApp().also { it.start() }
    }
    val settings by app.settings.settings.collectAsState()
    val dark = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> true
    }
    val french = when (settings.appLanguage) {
        "en" -> false
        "fr" -> true
        else -> Locale.getDefault().language.startsWith("fr")
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "WatchMate",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                AppShell(app, french)
            }
        }
    }
}

@Composable
private fun AppShell(app: DesktopApp, french: Boolean) {
    var tab by remember { mutableStateOf(Tab.Home) }
    Row(Modifier.fillMaxSize()) {
        NavigationRail(Modifier.fillMaxHeight()) {
            Tab.entries.forEach { t ->
                NavigationRailItem(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = { Text(t.name.first().toString()) },
                    label = { Text(if (french) t.labelFr else t.labelEn) },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
            when (tab) {
                Tab.Home -> HomeScreen(app, french)
                Tab.Explore -> PlaceholderScreen(
                    if (french) "Explorer" else "Explore",
                    if (french) {
                        "Parcourir catalogues et genres — à brancher comme sur Android."
                    } else {
                        "Browse catalogs and genres — same flow as Android, next."
                    },
                )
                Tab.Library -> LibraryScreen(app, french)
                Tab.Modules -> ModulesScreen(app, french)
                Tab.Settings -> SettingsScreen(app, french)
            }
        }
    }
}
