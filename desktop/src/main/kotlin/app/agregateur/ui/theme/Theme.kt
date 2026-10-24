package app.agregateur.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.agregateur.core.settings.ThemeMode

/** Couleurs hors du schéma Material. Mêmes valeurs que `res/values/colors.xml` de l'app Android. */
@Immutable
class WmColors(
    val synchro: Color,
    val contour: Color,
    val filet: Color,
    val carte: Color,
    val logeHaute: Color,
    val dark: Boolean,
)

private val LocalWmColors = staticCompositionLocalOf<WmColors> { error("WatchMateTheme manquant") }

val MaterialTheme.wm: WmColors
    @Composable @ReadOnlyComposable get() = LocalWmColors.current

private object Nuit {
    val velours = Color(0xFF1B1215)
    val carte = Color(0xFF241A1E)
    val loge = Color(0xFF2E2125)
    val logeHaute = Color(0xFF3A2A2F)
    val filet = Color(0xFF4A3A3F)
    val contour = Color(0xFF7A6A6E)
    val faisceau = Color(0xFFF3EBE3)
    val poussiere = Color(0xFFB0A3A6)
    val neon = Color(0xFFFF7A9C)
    val synchro = Color(0xFF7FD9C4)
    val alerte = Color(0xFFFF9A8A)
}

private object Jour {
    val entracte = Color(0xFFF7F0EC)
    val carte = Color(0xFFF0E6E1)
    val loge = Color(0xFFEADFDA)
    val logeHaute = Color(0xFFDDCFC9)
    val filet = Color(0xFFCDBFB9)
    val contour = Color(0xFF8C7B80)
    val texte = Color(0xFF1B1215)
    val poussiere = Color(0xFF6E5F63)
    val neon = Color(0xFFB3204F)
    val synchro = Color(0xFF17705F)
    val alerte = Color(0xFFB3261E)
}

private val darkScheme: ColorScheme = darkColorScheme(
    primary = Nuit.neon,
    onPrimary = Nuit.velours,
    primaryContainer = Nuit.logeHaute,
    onPrimaryContainer = Nuit.faisceau,
    secondary = Nuit.synchro,
    onSecondary = Nuit.velours,
    secondaryContainer = Nuit.loge,
    onSecondaryContainer = Nuit.faisceau,
    tertiary = Nuit.synchro,
    onTertiary = Nuit.velours,
    background = Nuit.velours,
    onBackground = Nuit.faisceau,
    surface = Nuit.velours,
    onSurface = Nuit.faisceau,
    surfaceVariant = Nuit.loge,
    onSurfaceVariant = Nuit.poussiere,
    surfaceTint = Nuit.neon,
    outline = Nuit.contour,
    outlineVariant = Nuit.logeHaute,
    error = Nuit.alerte,
    onError = Nuit.velours,
    surfaceContainerLowest = Nuit.velours,
    surfaceContainerLow = Nuit.carte,
    surfaceContainer = Nuit.carte,
    surfaceContainerHigh = Nuit.loge,
    surfaceContainerHighest = Nuit.loge,
)

private val lightScheme: ColorScheme = lightColorScheme(
    primary = Jour.neon,
    onPrimary = Jour.entracte,
    primaryContainer = Jour.logeHaute,
    onPrimaryContainer = Jour.texte,
    secondary = Jour.synchro,
    onSecondary = Jour.entracte,
    secondaryContainer = Jour.loge,
    onSecondaryContainer = Jour.texte,
    tertiary = Jour.synchro,
    onTertiary = Jour.entracte,
    background = Jour.entracte,
    onBackground = Jour.texte,
    surface = Jour.entracte,
    onSurface = Jour.texte,
    surfaceVariant = Jour.loge,
    onSurfaceVariant = Jour.poussiere,
    surfaceTint = Jour.neon,
    outline = Jour.contour,
    outlineVariant = Jour.logeHaute,
    error = Jour.alerte,
    onError = Jour.entracte,
    surfaceContainerLowest = Jour.entracte,
    surfaceContainerLow = Jour.carte,
    surfaceContainer = Jour.carte,
    surfaceContainerHigh = Jour.loge,
    surfaceContainerHighest = Jour.loge,
)

@Composable
fun WatchMateTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val extras = if (darkTheme) {
        WmColors(Nuit.synchro, Nuit.contour, Nuit.filet, Nuit.carte, Nuit.logeHaute, dark = true)
    } else {
        WmColors(Jour.synchro, Jour.contour, Jour.filet, Jour.carte, Jour.logeHaute, dark = false)
    }
    CompositionLocalProvider(LocalWmColors provides extras) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme else lightScheme,
            typography = WmTypography,
            shapes = WmShapes,
            content = content,
        )
    }
}
