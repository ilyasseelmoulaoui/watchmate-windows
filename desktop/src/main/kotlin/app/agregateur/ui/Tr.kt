package app.agregateur.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** Langue de l'interface, fournie à la racine : les écrans récents la lisent ici plutôt que par paramètre. */
val LocalFrench = staticCompositionLocalOf { true }

@Composable
@ReadOnlyComposable
fun tr(fr: String, en: String): String = if (LocalFrench.current) fr else en
