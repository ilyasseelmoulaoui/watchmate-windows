package app.agregateur.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Le rayon suit la hiérarchie : affiche 6, code 8, puce 10, champ 12, bouton 16, carte vedette 20, feuille 28. */
val WmShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

object WmShape {
    val Poster = RoundedCornerShape(6.dp)
    val Code = RoundedCornerShape(8.dp)
    val Chip = RoundedCornerShape(10.dp)
    val Field = RoundedCornerShape(12.dp)
    val Button = RoundedCornerShape(16.dp)
    val Feature = RoundedCornerShape(20.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}
