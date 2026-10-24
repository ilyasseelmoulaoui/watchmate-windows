package app.agregateur.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import app.agregateur.ui.theme.WatchMateMark
import app.agregateur.ui.theme.WmIcons
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Rectangle

/** Survol de Fermer : même rouge que la barre de titre du système. */
private val CloseHover = Color(0xFFC42B1C)

fun WindowState.toggleMaximized() {
    placement = if (placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
}

/**
 * Barre de titre maison de 48 dp peinte en Velours. Tout ce qui n'est pas un contrôle déplace la fenêtre ;
 * double-clic = agrandir.
 */
@Composable
fun FrameWindowScope.TitleBar(
    state: WindowState,
    french: Boolean,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    center: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(49.dp).background(colors.background)) {
        WindowDraggableArea(
            Modifier.fillMaxSize().pointerInput(state) { detectTapGestures(onDoubleTap = { state.toggleMaximized() }) },
        ) { Box(Modifier.fillMaxSize()) }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onBack != null) {
                    CaptionButton(if (french) "Retour (Alt+Gauche)" else "Back (Alt+Left)", onBack, width = 48.dp) { tint ->
                        Icon(WmIcons.Back, null, tint = tint, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Spacer(Modifier.width(6.dp))
                }
                WatchMateMark(size = 18.dp)
                Text("WatchMate", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Box(Modifier.padding(horizontal = 16.dp).widthIn(max = 480.dp).fillMaxWidth()) { center() }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                CaptionButton(if (french) "Réduire" else "Minimize", { state.isMinimized = true }) { tint ->
                    Glyph(tint) { stroke -> drawLine(tint, Offset(0f, size.height * 0.55f), Offset(size.width, size.height * 0.55f), stroke.width) }
                }
                val maximized = state.placement == WindowPlacement.Maximized
                CaptionButton(
                    if (maximized) (if (french) "Restaurer" else "Restore") else (if (french) "Agrandir" else "Maximize"),
                    { state.toggleMaximized() },
                ) { tint ->
                    Glyph(tint) { stroke ->
                        if (maximized) {
                            val s = size.width * 0.78f
                            val o = size.width - s
                            drawRect(tint, Offset(0f, o), androidx.compose.ui.geometry.Size(s, s), style = stroke)
                            drawLine(tint, Offset(o, o), Offset(o, 0f), stroke.width)
                            drawLine(tint, Offset(o, 0f), Offset(size.width, 0f), stroke.width)
                            drawLine(tint, Offset(size.width, 0f), Offset(size.width, s), stroke.width)
                            drawLine(tint, Offset(size.width, s), Offset(s, s), stroke.width)
                        } else {
                            drawRect(tint, style = stroke)
                        }
                    }
                }
                CaptionButton(if (french) "Fermer" else "Close", onClose, hover = CloseHover, hoverTint = Color.White) { tint ->
                    Glyph(tint) { stroke ->
                        drawLine(tint, Offset(0f, 0f), Offset(size.width, size.height), stroke.width)
                        drawLine(tint, Offset(size.width, 0f), Offset(0f, size.height), stroke.width)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(colors.surfaceVariant))
    }
}

@Composable
private fun CaptionButton(
    label: String,
    onClick: () -> Unit,
    width: Dp = 46.dp,
    hover: Color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
    hoverTint: Color = MaterialTheme.colorScheme.onBackground,
    content: @Composable (Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .width(width)
            .height(48.dp)
            .hoverable(interaction)
            .background(if (hovered) hover else Color.Transparent)
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        content(if (hovered) hoverTint else MaterialTheme.colorScheme.onBackground)
    }
}

/** Glyphes des boutons de légende : 10 × 10, trait de 1 px. */
@Composable
private fun Glyph(tint: Color, draw: DrawScope.(Stroke) -> Unit) {
    Canvas(Modifier.size(10.dp)) { draw(Stroke(width = 1.dp.toPx().coerceAtLeast(1f))) }
}

private enum class Edge(val cursor: Int, val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
    Left(Cursor.W_RESIZE_CURSOR, true, false, false, false),
    Right(Cursor.E_RESIZE_CURSOR, false, false, true, false),
    Top(Cursor.N_RESIZE_CURSOR, false, true, false, false),
    Bottom(Cursor.S_RESIZE_CURSOR, false, false, false, true),
    TopLeft(Cursor.NW_RESIZE_CURSOR, true, true, false, false),
    TopRight(Cursor.NE_RESIZE_CURSOR, false, true, true, false),
    BottomLeft(Cursor.SW_RESIZE_CURSOR, true, false, false, true),
    BottomRight(Cursor.SE_RESIZE_CURSOR, false, false, true, true),
}

/**
 * Une fenêtre sans décoration système ne se redimensionne plus par ses bords : poignées de 5 dp posées par-dessus
 * le contenu. Les positions viennent de l'écran (et non du composant, qui bouge avec la fenêtre).
 */
@Composable
fun FrameWindowScope.ResizeEdges(state: WindowState) {
    if (state.placement != WindowPlacement.Floating) return
    val thickness = 5.dp
    val corner = 10.dp
    Box(Modifier.fillMaxSize()) {
        Handle(Edge.Left, Modifier.align(Alignment.CenterStart).width(thickness).fillMaxHeight())
        Handle(Edge.Right, Modifier.align(Alignment.CenterEnd).width(thickness).fillMaxHeight())
        Handle(Edge.Top, Modifier.align(Alignment.TopCenter).height(thickness).fillMaxWidth())
        Handle(Edge.Bottom, Modifier.align(Alignment.BottomCenter).height(thickness).fillMaxWidth())
        Handle(Edge.TopLeft, Modifier.align(Alignment.TopStart).size(corner))
        Handle(Edge.TopRight, Modifier.align(Alignment.TopEnd).size(corner))
        Handle(Edge.BottomLeft, Modifier.align(Alignment.BottomStart).size(corner))
        Handle(Edge.BottomRight, Modifier.align(Alignment.BottomEnd).size(corner))
    }
}

@Composable
private fun FrameWindowScope.Handle(edge: Edge, modifier: Modifier) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor(edge.cursor)))
            .pointerInput(edge) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    val start = MouseInfo.getPointerInfo().location
                    val bounds = window.bounds
                    val min = window.minimumSize
                    do {
                        val event = awaitPointerEvent()
                        val now = MouseInfo.getPointerInfo()?.location ?: break
                        window.bounds = resized(bounds, edge, now.x - start.x, now.y - start.y, min.width, min.height)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
    )
}

private fun resized(b: Rectangle, edge: Edge, dx: Int, dy: Int, minW: Int, minH: Int): Rectangle {
    var x = b.x
    var y = b.y
    var w = b.width
    var h = b.height
    if (edge.right) w = (b.width + dx).coerceAtLeast(minW)
    if (edge.bottom) h = (b.height + dy).coerceAtLeast(minH)
    if (edge.left) {
        w = (b.width - dx).coerceAtLeast(minW)
        x = b.x + b.width - w
    }
    if (edge.top) {
        h = (b.height - dy).coerceAtLeast(minH)
        y = b.y + b.height - h
    }
    return Rectangle(x, y, w, h)
}
