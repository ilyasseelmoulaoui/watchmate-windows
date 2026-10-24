package app.agregateur.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Typeface
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation

/**
 * Les deux polices sont variables (mêmes fichiers que l'app Android). Compose Desktop ne règle pas l'axe
 * `wght` d'une police chargée depuis une ressource : on clone l'instance Skia à chaque graisse utilisée.
 */
private class VariableFont(private val resource: String) {
    private val base by lazy {
        val bytes = requireNotNull(VariableFont::class.java.getResourceAsStream(resource)) { resource }.use { it.readBytes() }
        requireNotNull(FontMgr.default.makeFromData(Data.makeFromBytes(bytes))) { resource }
    }
    private val families = ConcurrentHashMap<Int, FontFamily>()

    fun at(weight: Int): FontFamily = families.getOrPut(weight) {
        FontFamily(Typeface(base.makeClone(FontVariation("wght", weight.toFloat())), "$resource#$weight"))
    }
}

/** Générique : titres d'œuvres et code de salle. */
private val Generique = VariableFont("/fonts/big_shoulders_display.ttf")

/** Interface. */
private val Interface = VariableFont("/fonts/hanken_grotesk.ttf")

/** Famille d'interface à une graisse donnée (police variable : une instance par graisse). */
fun interfaceFamily(weight: Int): FontFamily = Interface.at(weight)

/** Famille du générique à une graisse donnée. */
fun generiqueFamily(weight: Int): FontFamily = Generique.at(weight)

private fun ui(weight: Int, size: Int, line: Int) = TextStyle(
    fontFamily = Interface.at(weight),
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
)

private fun generique(weight: Int, size: Int, line: Int, spacing: TextUnit = 0.sp) = TextStyle(
    fontFamily = Generique.at(weight),
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing,
)

/** Rôles du générique, hors échelle Material. Tailles de la maquette Windows (fenêtre 1440 × 900). */
object WmType {
    val TitreFiche = generique(900, 160, 136, 1.sp)
    val TitreFicheMoyen = generique(900, 104, 92, 0.5.sp)
    val TitreFicheLong = generique(900, 72, 66)
    val Vedette = generique(800, 72, 64)
    val Module = generique(800, 48, 46)
    val GeneriqueXl = generique(800, 56, 52)
    val GeneriqueL = generique(800, 40, 38)
    val Affiche = generique(800, 30, 28)
    val Lecteur = generique(800, 34, 32)
    val Pastille = generique(800, 22, 22)
    val GeneriqueS = generique(700, 20, 20)
    val CodeSalle = generique(800, 34, 40)
}

val WmTypography = Typography(
    displayLarge = WmType.TitreFiche,
    displayMedium = WmType.GeneriqueXl,
    displaySmall = WmType.GeneriqueL,
    headlineLarge = WmType.GeneriqueXl,
    headlineMedium = WmType.GeneriqueL,
    headlineSmall = generique(800, 28, 28),
    titleLarge = ui(600, 22, 28),
    titleMedium = ui(600, 17, 24),
    titleSmall = ui(600, 15, 20),
    bodyLarge = ui(400, 17, 26),
    bodyMedium = ui(400, 15, 22),
    bodySmall = ui(400, 13, 18),
    labelLarge = ui(600, 14, 20),
    labelMedium = ui(500, 13, 18),
    labelSmall = ui(600, 12, 16),
)
