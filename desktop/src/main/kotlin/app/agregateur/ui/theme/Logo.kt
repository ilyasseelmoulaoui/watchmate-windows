package app.agregateur.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Symbole WatchMate : un W et son reflet, qui se touchent en deux points. */
@Composable
fun WatchMateMark(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val haut = MaterialTheme.colorScheme.onBackground
    val bas = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(size)) {
        val k = this.size.width / 108f
        fun path(vararg points: Pair<Float, Float>) = Path().apply {
            moveTo(points[0].first * k, points[0].second * k)
            for (i in 1 until points.size) lineTo(points[i].first * k, points[i].second * k)
        }
        val stroke = Stroke(width = 11f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path(30f to 32f, 42f to 54f, 54f to 43f, 66f to 54f, 78f to 32f), haut, style = stroke)
        drawPath(path(30f to 76f, 42f to 54f, 54f to 65f, 66f to 54f, 78f to 76f), bas, style = stroke)
    }
}
