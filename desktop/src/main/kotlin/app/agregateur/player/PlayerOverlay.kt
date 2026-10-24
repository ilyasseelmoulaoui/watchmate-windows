package app.agregateur.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agregateur.core.playback.PlayerControls
import app.agregateur.core.playback.SubtitleBackground
import app.agregateur.core.playback.SubtitleColor
import app.agregateur.core.playback.SubtitleStyle
import app.agregateur.core.playback.VideoFit
import app.agregateur.ui.theme.interfaceFamily
import app.agregateur.ui.theme.WmIcons
import app.agregateur.ui.theme.WmShape
import app.agregateur.ui.tr
import app.agregateur.ui.wmClickable
import kotlin.math.abs
import kotlin.math.roundToInt

/** Couleurs du lecteur : toujours la palette sombre, quel que soit le thème choisi. */
object PlayerPalette {
    val velours = Color(0xFF1B1215)
    val loge = Color(0xFF2E2125)
    val logeHaute = Color(0xFF3A2A2F)
    val contour = Color(0xFF7A6A6E)
    val faisceau = Color(0xFFF3EBE3)
    val poussiere = Color(0xFFB0A3A6)
    val neon = Color(0xFFFF7A9C)
    val synchro = Color(0xFF7FD9C4)
    val alerte = Color(0xFFFF9A8A)
}

private val P = PlayerPalette

fun subtitleColor(color: SubtitleColor): Color = when (color) {
    SubtitleColor.WHITE -> Color(0xFFFFFFFF)
    SubtitleColor.YELLOW -> Color(0xFFFFE15A)
    SubtitleColor.CYAN -> Color(0xFF7FE3F0)
    SubtitleColor.GREEN -> Color(0xFF9BE58C)
    SubtitleColor.PINK -> Color(0xFFFF7A9C)
}

/** Bouton rond translucide des commandes du lecteur (48 dp au moins, comme sur la maquette). */
@Composable
fun GhostButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    badge: String? = null,
    active: Boolean = false,
) {
    Box(
        Modifier
            .size(size)
            .wmClickable(CircleShape, hover = P.faisceau.copy(alpha = 0.12f), onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (active) P.neon else P.faisceau, modifier = Modifier.size(iconSize))
        if (badge != null) {
            Text(
                badge,
                color = P.velours,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 4.dp)
                    .background(P.neon, RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * Barre de position : survol = heure visée, clic = saut, glisser = défilement. Le trait Néon montre la lecture,
 * le trait clair la partie déjà chargée n'est pas connue avec VLC : la piste reste d'une seule teinte.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onScrub: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableStateOf(IntSize.Zero) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var dragMs by remember { mutableStateOf<Long?>(null) }
    fun at(x: Float): Long = if (durationMs <= 0 || width.width == 0) 0L else ((x / width.width).coerceIn(0f, 1f) * durationMs).toLong()
    val shown = dragMs ?: positionMs
    val fraction = if (durationMs > 0) (shown.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val density = LocalDensity.current
    Box(
        modifier
            .height(32.dp)
            .onSizeChanged { width = it }
            .onPointerEvent(PointerEventType.Move) { hoverX = it.changes.first().position.x }
            .onPointerEvent(PointerEventType.Exit) { hoverX = null }
            .pointerInput(durationMs) { detectTapGestures { onSeek(at(it.x)) } }
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { dragMs = at(it.x); onScrub(dragMs) },
                    onDragEnd = { dragMs?.let(onSeek); dragMs = null; onScrub(null) },
                    onDragCancel = { dragMs = null; onScrub(null) },
                    onDrag = { change, _ -> dragMs = at(change.position.x); onScrub(dragMs) },
                )
            }
            .semantics { contentDescription = "Position" },
        contentAlignment = Alignment.CenterStart,
    ) {
        val thick = hoverX != null || dragMs != null
        val track = if (thick) 6.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(track).clip(RoundedCornerShape(3.dp)).background(P.faisceau.copy(alpha = 0.3f)))
        Box(Modifier.fillMaxWidth(fraction).height(track).clip(RoundedCornerShape(3.dp)).background(P.neon))
        Box(
            Modifier
                .offset(x = with(density) { (fraction * width.width).toDp() } - 2.dp)
                .width(4.dp)
                .height(if (thick) 22.dp else 18.dp)
                .background(P.faisceau, RoundedCornerShape(2.dp)),
        )
        val x = hoverX
        if (x != null && dragMs == null && durationMs > 0) {
            Text(
                formatTime(at(x)),
                color = P.faisceau,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .offset(x = with(density) { x.toDp() } - 24.dp, y = (-28).dp)
                    .background(P.velours.copy(alpha = 0.85f), WmShape.Chip)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Panneau latéral du lecteur (sous-titres, audio, lecture) : glisse depuis la droite, fond Velours. */
@Composable
fun BoxScope.SidePanel(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (visible) {
        Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
    }
    AnimatedVisibility(
        visible,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
        modifier = Modifier.align(Alignment.CenterEnd),
    ) {
        Column(
            Modifier
                .width(380.dp)
                .fillMaxHeight()
                .background(P.velours.copy(alpha = 0.96f))
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun PanelTitle(text: String, onBack: (() -> Unit)? = null, onClose: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onBack != null) GhostButton(WmIcons.Back, tr("Retour", "Back"), onBack, size = 40.dp, iconSize = 18.dp)
        Text(text, style = MaterialTheme.typography.titleLarge, color = P.faisceau, modifier = Modifier.weight(1f))
        if (onClose != null) GhostButton(WmIcons.Clear, tr("Fermer", "Close"), onClose, size = 40.dp, iconSize = 18.dp)
    }
}

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = P.faisceau, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = P.poussiere)
    }
}

@Composable
fun ChoiceRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (selected) Modifier.background(P.loge, WmShape.Chip) else Modifier)
            .wmClickable(WmShape.Chip, hover = P.loge, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = P.faisceau)
            if (detail != null) Text(detail, style = MaterialTheme.typography.labelMedium, color = P.poussiere)
        }
        if (selected) Icon(WmIcons.Check, null, tint = P.neon, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipChoices(options: List<T>, selected: T?, label: @Composable (T) -> String, onSelect: (T) -> Unit, enabled: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val active = option == selected
            Box(
                Modifier
                    .height(40.dp)
                    .then(if (active) Modifier.background(P.faisceau, WmShape.Chip) else Modifier.border(1.dp, P.logeHaute, WmShape.Chip))
                    .wmClickable(WmShape.Chip, hover = if (active) Color.Transparent else P.loge, enabled = enabled) { onSelect(option) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        !enabled -> P.poussiere.copy(alpha = 0.6f)
                        active -> P.velours
                        else -> P.faisceau
                    },
                )
            }
        }
    }
}

@Composable
fun sliderColors() = SliderDefaults.colors(
    thumbColor = P.neon,
    activeTrackColor = P.neon,
    inactiveTrackColor = P.logeHaute,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

@Composable
fun SubtitleStyleEditor(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit) {
    SubtitlePreview(style)
    SectionLabel(tr("Taille du texte", "Text size"), "${style.sizePercent} %")
    Slider(
        value = style.sizePercent.toFloat(),
        onValueChange = { onChange(style.copy(sizePercent = (it / 10f).roundToInt() * 10).normalized()) },
        valueRange = SubtitleStyle.MIN_SIZE.toFloat()..SubtitleStyle.MAX_SIZE.toFloat(),
        colors = sliderColors(),
    )
    SectionLabel(tr("Couleur", "Color"))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SubtitleColor.entries.forEach { color ->
            val active = color == style.color
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(if (active) 3.dp else 1.dp, if (active) P.neon else P.poussiere.copy(alpha = 0.5f), CircleShape)
                    .wmClickable(CircleShape, hover = Color.Transparent, role = Role.RadioButton) { onChange(style.copy(color = color)) }
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(subtitleColor(color)),
            )
        }
    }
    SectionLabel(tr("Fond", "Background"))
    ChipChoices(
        options = SubtitleBackground.entries,
        selected = style.background,
        label = {
            when (it) {
                SubtitleBackground.NONE -> tr("Aucun", "None")
                SubtitleBackground.TRANSLUCENT -> tr("Voile", "Translucent")
                SubtitleBackground.OPAQUE -> tr("Plein", "Solid")
            }
        },
        onSelect = { onChange(style.copy(background = it)) },
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Contour", "Outline"), style = MaterialTheme.typography.titleSmall, color = P.faisceau, modifier = Modifier.weight(1f))
        Switch(
            checked = style.outline,
            onCheckedChange = { onChange(style.copy(outline = it)) },
            colors = SwitchDefaults.colors(
                checkedTrackColor = P.neon,
                checkedThumbColor = P.velours,
                uncheckedTrackColor = P.logeHaute,
                uncheckedThumbColor = P.poussiere,
                uncheckedBorderColor = P.poussiere,
            ),
        )
    }
    SectionLabel(tr("Hauteur à l’écran", "Position on screen"), "${style.bottomPercent} %")
    Slider(
        value = style.bottomPercent.toFloat(),
        onValueChange = { onChange(style.copy(bottomPercent = it.roundToInt()).normalized()) },
        valueRange = 0f..SubtitleStyle.MAX_BOTTOM.toFloat(),
        colors = sliderColors(),
    )
    Text(
        tr(
            "Les sous-titres des modules suivent ces réglages tout de suite ; ceux intégrés au fichier, à la prochaine lecture.",
            "Module subtitles follow these settings right away; subtitles embedded in the file, from the next playback.",
        ),
        style = MaterialTheme.typography.labelMedium,
        color = P.poussiere,
    )
}

@Composable
private fun SubtitlePreview(style: SubtitleStyle) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(WmShape.Field)
            .background(Brush.verticalGradient(listOf(P.logeHaute, P.velours))),
        contentAlignment = Alignment.Center,
    ) {
        SubtitleText(tr("Voici à quoi ressembleront vos sous-titres.", "This is how your subtitles will look."), style, baseSize = 16f)
    }
}

/** Texte de sous-titre dessiné par l'application, avec le style des réglages. */
@Composable
fun SubtitleText(text: String, style: SubtitleStyle, baseSize: Float, modifier: Modifier = Modifier) {
    val outline = Color.Black
    val background = when (style.background) {
        SubtitleBackground.NONE -> Color.Transparent
        SubtitleBackground.TRANSLUCENT -> outline.copy(alpha = 0.55f)
        SubtitleBackground.OPAQUE -> outline
    }
    val size = baseSize * style.sizePercent / 100f
    Text(
        text,
        style = TextStyle(
            fontFamily = interfaceFamily(600),
            fontWeight = FontWeight.SemiBold,
            fontSize = size.sp,
            lineHeight = (size * 1.3f).sp,
            color = subtitleColor(style.color),
            textAlign = TextAlign.Center,
            shadow = if (style.outline) Shadow(outline, Offset(0f, 0f), blurRadius = 6f) else null,
        ),
        modifier = modifier.background(background, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

@Composable
fun DelayControls(delayMs: Long, onChange: (Long) -> Unit) {
    SectionLabel(tr("Décalage des sous-titres", "Subtitle delay"), formatDelay(delayMs))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(-1_000L to "−1 s", -100L to "−0,1", 100L to "+0,1", 1_000L to "+1 s").forEach { (step, label) ->
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(P.logeHaute, WmShape.Chip)
                    .wmClickable(WmShape.Chip, hover = P.contour.copy(alpha = 0.4f)) { onChange(PlayerControls.stepDelay(delayMs, step)) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = P.faisceau)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            tr("Positif : les sous-titres apparaissent plus tard. Négatif : plus tôt. Touches G et H.", "Positive: subtitles show later. Negative: earlier. Keys G and H."),
            style = MaterialTheme.typography.labelMedium,
            color = P.poussiere,
            modifier = Modifier.weight(1f),
        )
        if (delayMs != 0L) {
            Text(
                tr("Réinitialiser", "Reset"),
                color = P.neon,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.wmClickable(WmShape.Chip, hover = P.loge) { onChange(0L) }.padding(8.dp),
            )
        }
    }
}

fun formatDelay(ms: Long): String {
    val text = "%.1f".format(abs(ms / 1000.0)).replace('.', ',')
    return when {
        ms > 0 -> "+$text s"
        ms < 0 -> "−$text s"
        else -> "0 s"
    }
}

fun formatSpeed(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')).replace('.', ',') + "×"

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
fun fitLabel(fit: VideoFit): String = when (fit) {
    VideoFit.FIT -> tr("Image entière", "Fit")
    VideoFit.ZOOM -> tr("Remplir (recadrer)", "Fill (crop)")
    VideoFit.STRETCH -> tr("Étirer", "Stretch")
}

@Composable
fun Scrims() {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(140.dp)
                .background(Brush.verticalGradient(listOf(P.velours.copy(alpha = 0.8f), Color.Transparent))),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(200.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, P.velours.copy(alpha = 0.9f)))),
        )
    }
}

/** Bulle centrale brève : « +10 s », vitesse, volume… */
@Composable
fun CenterHud(text: String, detail: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.background(P.velours.copy(alpha = 0.75f), WmShape.Field).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = P.faisceau)
        if (detail != null) Text(detail, style = MaterialTheme.typography.labelMedium, color = P.poussiere)
    }
}

@Composable
fun VolumeControl(volume: Int, muted: Boolean, onVolume: (Int) -> Unit, onMute: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GhostButton(
            if (muted || volume == 0) WmIcons.Mute else WmIcons.Audio,
            if (muted) tr("Rétablir le son (M)", "Unmute (M)") else tr("Couper le son (M)", "Mute (M)"),
            onMute,
        )
        Slider(
            value = if (muted) 0f else volume.toFloat(),
            onValueChange = { onVolume(it.roundToInt()) },
            valueRange = 0f..PlayerController.MAX_VOLUME.toFloat(),
            colors = sliderColors(),
            modifier = Modifier.width(110.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("${if (muted) 0 else volume} %", style = MaterialTheme.typography.labelMedium, color = P.poussiere, modifier = Modifier.widthIn(min = 40.dp))
    }
}
