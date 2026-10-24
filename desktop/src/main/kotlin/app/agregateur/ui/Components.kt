package app.agregateur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.agregateur.ui.theme.WmIcons
import app.agregateur.ui.theme.WmShape
import app.agregateur.ui.theme.WmType
import app.agregateur.ui.theme.wm

/** Vrai après Tab, faux au premier clic : l'anneau de focus ne s'affiche que pour la navigation au clavier. */
object FocusVisibility {
    var keyboard by mutableStateOf(false)
}

/**
 * Cliquable façon bureau : fond au survol, curseur main, anneau Néon de 2 dp au focus clavier, pas d'onde Material.
 */
@Composable
fun Modifier.wmClickable(
    shape: Shape = RectangleShape,
    hover: Color = MaterialTheme.colorScheme.surfaceVariant,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val ring = MaterialTheme.colorScheme.primary
    return this
        .clip(shape)
        .then(if (hovered && enabled) Modifier.background(hover, shape) else Modifier)
        .then(if (focused && FocusVisibility.keyboard) Modifier.border(2.dp, ring, shape) else Modifier)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = modifier)
}

/** Titre de rangée (17/600) suivi de son module en méta. */
@Composable
fun SectionTitle(text: String, meta: String? = null, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        if (meta != null) {
            Text(
                meta,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun ErrorLine(text: String, french: Boolean, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f, fill = false))
        if (onRetry != null) QuietButton(if (french) "Réessayer" else "Retry", onRetry)
    }
}

/** Action principale : aplat Néon, texte Velours (7,5:1). */
@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
) {
    val colors = MaterialTheme.colorScheme
    val container = if (enabled) colors.primary else colors.surfaceVariant
    val content = if (enabled) colors.onPrimary else colors.onSurfaceVariant
    Row(
        modifier
            .height(height)
            .background(container, WmShape.Button)
            .wmClickable(WmShape.Button, hover = colors.onPrimary.copy(alpha = 0.08f), enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = content, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** Action secondaire : contour 1 dp, fond transparent. */
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .height(height)
            .border(1.dp, MaterialTheme.wm.contour, WmShape.Button)
            .wmClickable(WmShape.Button, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) colors.onBackground else colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .height(40.dp)
            .wmClickable(WmShape.Chip, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = colors.onBackground, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) colors.onBackground else colors.onSurfaceVariant)
    }
}

@Composable
fun SquareIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    Box(
        modifier
            .size(size)
            .border(1.dp, MaterialTheme.wm.contour, WmShape.Button)
            .wmClickable(WmShape.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
    }
}

/** Puce de filtre : rayon 10, hauteur 40 ; sélection = aplat Faisceau avec coche. */
@Composable
fun WmChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val base = if (selected) {
        Modifier.background(colors.onBackground, WmShape.Chip)
    } else {
        Modifier.border(1.dp, MaterialTheme.wm.filet, WmShape.Chip)
    }
    Row(
        modifier
            .height(40.dp)
            .then(base)
            .wmClickable(WmShape.Chip, hover = if (selected) Color.Transparent else colors.surfaceVariant, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) Icon(WmIcons.Check, null, tint = colors.background, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.background else colors.onBackground,
            maxLines = 1,
        )
    }
}

/** Bascule à deux ou trois choix (Films / Séries, Ma liste / Reprendre). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val inner = RoundedCornerShape(9.dp)
    Row(modifier.background(colors.surfaceVariant, WmShape.Field).padding(4.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier
                    .height(40.dp)
                    .then(if (active) Modifier.background(colors.onBackground, inner) else Modifier)
                    .wmClickable(inner, hover = if (active) Color.Transparent else MaterialTheme.wm.logeHaute, role = Role.Tab) { onSelect(value) }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) colors.background else colors.onBackground)
            }
        }
    }
}

/** Champ : rayon 12, hauteur 48, fond Loge, filet 1 dp. Entrée valide via `onSubmit`. */
@Composable
fun WmTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSubmit: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onBackground),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier.onPreviewKeyEvent {
            if (onSubmit != null && it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                onSubmit()
                true
            } else {
                false
            }
        },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(colors.surfaceVariant, WmShape.Field)
                    .border(1.dp, MaterialTheme.wm.filet, WmShape.Field)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
                }
                inner()
            }
        },
    )
}

/** Trait de progression de 3 dp, Néon sur piste discrète. */
@Composable
fun ProgressLine(ratio: Float, modifier: Modifier = Modifier, track: Color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f)) {
    val shape = RoundedCornerShape(2.dp)
    Box(modifier.height(3.dp).background(track, shape)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(ratio.coerceIn(0f, 1f)).background(MaterialTheme.colorScheme.primary, shape))
    }
}

private val PosterPalette = listOf(
    Color(0xFF33457A) to false,
    Color(0xFF6B2D3A) to false,
    Color(0xFFC9A227) to true,
    Color(0xFF15141C) to false,
    Color(0xFFD9C7A7) to true,
    Color(0xFF2F4A3A) to false,
    Color(0xFF4B3F72) to false,
    Color(0xFF2F4A55) to false,
    Color(0xFF5E6B66) to true,
    Color(0xFF8A4A2A) to false,
)

/** Aplat de remplacement quand le module ne fournit pas d'image : couleur stable par titre, nom en générique. */
@Composable
fun BoxScope.PosterArt(name: String, seed: String, style: TextStyle = WmType.Affiche, alignTop: Boolean = false) {
    val (color, lightBackground) = PosterPalette[Math.floorMod(seed.hashCode(), PosterPalette.size)]
    Box(Modifier.matchParentSize().background(color))
    Text(
        name,
        style = style,
        color = if (lightBackground) Color(0xFF1B1215) else Color(0xFFF3EBE3),
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .align(if (alignTop) Alignment.TopStart else Alignment.BottomStart)
            .padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
    )
}

/** Affiche cliquable (rayon 6). L'image du module la remplit ; sinon l'aplat porte le nom. */
@Composable
fun Poster(
    name: String,
    poster: String?,
    seed: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = WmShape.Poster,
    style: TextStyle = WmType.Affiche,
    artName: Boolean = true,
    artOnTop: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier
            .clip(shape)
            .wmClickable(shape, hover = Color.Transparent, onClick = onClick)
            .semantics { contentDescription = name },
    ) {
        RemoteImage(poster, null, Modifier.fillMaxSize()) { PosterArt(if (artName) name else "", seed, style, artOnTop) }
        overlay()
    }
}

/** Rangée horizontale d'affiches 176 × 264. */
@Composable
fun <T> PosterRow(
    items: List<T>,
    key: (T) -> String,
    name: (T) -> String,
    poster: (T) -> String?,
    onOpen: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(end = 40.dp),
    ) {
        items(items, key = key) { item ->
            Poster(
                name = name(item),
                poster = poster(item),
                seed = key(item),
                onClick = { onOpen(item) },
                modifier = Modifier.width(176.dp).height(264.dp),
            )
        }
    }
}

/** Affiche « Reprendre » : progression + Retirer (efface l'historique de lecture du titre). */
@Composable
fun ResumePoster(
    name: String,
    poster: String?,
    seed: String,
    progressRatio: Float,
    french: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier.width(176.dp),
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Poster(
            name = name,
            poster = poster,
            seed = seed,
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth().height(264.dp),
        ) {
            ProgressLine(progressRatio, Modifier.align(Alignment.BottomStart).fillMaxWidth())
        }
        QuietButton(if (french) "Retirer" else "Remove", onRemove)
    }
}

/** Pastille carrée d'un module : initiale en générique sur une couleur stable. */
@Composable
fun ModuleTile(name: String, seed: String, size: Dp, radius: Dp, style: TextStyle = WmType.Pastille) {
    val (color, lightBackground) = PosterPalette[Math.floorMod(seed.hashCode(), PosterPalette.size)]
    Box(
        Modifier.size(size).background(color, RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            style = style,
            color = if (lightBackground) Color(0xFF1B1215) else Color(0xFFF3EBE3),
        )
    }
}
