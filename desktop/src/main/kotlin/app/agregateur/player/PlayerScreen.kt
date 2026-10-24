package app.agregateur.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.agregateur.DesktopApp
import app.agregateur.core.party.PartyChatLine
import app.agregateur.core.party.PartyNotice
import app.agregateur.core.playback.PlayerControls
import app.agregateur.core.playback.VideoFit
import app.agregateur.party.PartyPanel
import app.agregateur.party.PartyState
import app.agregateur.party.mediaLabel
import app.agregateur.party.membersCount
import app.agregateur.party.noticeText
import app.agregateur.ui.NeonButton
import app.agregateur.ui.OutlineButton
import app.agregateur.ui.episodeCode
import app.agregateur.ui.theme.WmIcons
import app.agregateur.ui.theme.WmShape
import app.agregateur.ui.theme.generiqueFamily
import app.agregateur.ui.tr
import java.awt.Cursor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Panel { SUBTITLES, STYLE, AUDIO, PLAYBACK }

/** Ce que le lecteur peut demander à la fenêtre. */
interface WindowControls {
    val fullscreen: Boolean
    val compact: Boolean
    fun toggleFullscreen()
    fun toggleCompact()
}

private val P = PlayerPalette

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    app: DesktopApp,
    request: PlayRequest,
    window: WindowControls,
    onClose: () -> Unit,
    onJoinMedia: () -> Unit,
    joining: Boolean,
) {
    val controller = remember(request.launchId) { PlayerController(app, request) }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.stop() }
    }
    val settings by app.settings.settings.collectAsState()
    val partyState by app.party.state.collectAsState()
    val room = partyState as? PartyState.InRoom
    val chat by app.party.chat.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var fit by remember { mutableStateOf(VideoFit.FIT) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var partyOpen by remember { mutableStateOf(room != null) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var sleepAt by remember { mutableLongStateOf(0L) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var hud by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var hudTick by remember { mutableIntStateOf(0) }
    var chatSeenAt by remember { mutableLongStateOf(chat.lastOrNull()?.atMs ?: 0L) }

    LaunchedEffect(partyOpen, chat) { if (partyOpen) chat.lastOrNull()?.let { chatSeenAt = it.atMs } }
    LaunchedEffect(room != null) { if (room == null) partyOpen = false }
    val unread = room?.let { r -> chat.count { it.atMs > chatSeenAt && it.from != r.selfId } } ?: 0

    fun touched() {
        controlsVisible = true
        interaction++
    }
    fun flash(text: String, detail: String? = null) {
        hud = text to detail
        hudTick++
    }
    fun skip(deltaMs: Long) {
        val target = PlayerControls.seekBy(controller.positionMs, deltaMs, controller.durationMs)
        controller.seekTo(target)
        flash(if (deltaMs > 0) "+${deltaMs / 1000} s" else "−${-deltaMs / 1000} s", formatTime(target))
        touched()
    }
    fun volumeBy(delta: Int) {
        controller.changeVolume(controller.volume + delta)
        flash(tr2(app, "Volume", "Volume"), "${controller.volume} %")
    }
    fun speedBy(step: Int) {
        if (room != null) return
        val speeds = PlayerControls.SPEEDS
        val index = (speeds.indexOfFirst { it >= controller.speed }.takeIf { it >= 0 } ?: speeds.lastIndex) + step
        val next = speeds[index.coerceIn(0, speeds.lastIndex)]
        controller.setPlaybackSpeed(next)
        flash(formatSpeed(next))
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(panel) { if (panel == null) runCatching { focus.requestFocus() } }
    LaunchedEffect(controlsVisible, controller.playing, panel, interaction, scrubMs, locked) {
        if (controlsVisible && panel == null && scrubMs == null && (controller.playing || locked)) {
            delay(if (locked) 2_000 else 3_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(hudTick) {
        if (hud != null) {
            delay(900)
            hud = null
        }
    }
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0) {
            delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0))
            controller.setPlayWhenReady(false)
            sleepAt = 0L
            sleepMinutes = 0
        }
    }

    val keys = Modifier.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed) return@onKeyEvent false
        if (locked) {
            if (event.key == Key.Escape) touched()
            return@onKeyEvent event.key != Key.F11
        }
        touched()
        when (event.key) {
            Key.Spacebar, Key.K, Key.MediaPlayPause -> controller.togglePlay()
            Key.MediaPlay -> controller.setPlayWhenReady(true)
            Key.MediaPause -> controller.setPlayWhenReady(false)
            Key.DirectionLeft, Key.J -> skip(if (event.isShiftPressed) -60_000 else -PlayerControls.DOUBLE_TAP_SEEK_MS)
            Key.DirectionRight, Key.L -> skip(if (event.isShiftPressed) 60_000 else PlayerControls.DOUBLE_TAP_SEEK_MS)
            Key.DirectionUp -> volumeBy(5)
            Key.DirectionDown -> volumeBy(-5)
            Key.M -> controller.setMute(!controller.muted)
            Key.F, Key.F11 -> window.toggleFullscreen()
            Key.S -> panel = Panel.SUBTITLES
            Key.A -> panel = Panel.AUDIO
            Key.G -> controller.setSubtitleDelay(PlayerControls.stepDelay(controller.subtitleDelayMs, -100)).also { flash(formatDelay(controller.subtitleDelayMs)) }
            Key.H -> controller.setSubtitleDelay(PlayerControls.stepDelay(controller.subtitleDelayMs, 100)).also { flash(formatDelay(controller.subtitleDelayMs)) }
            Key.LeftBracket, Key.Minus -> speedBy(-1)
            Key.RightBracket, Key.Equals -> speedBy(1)
            Key.Home -> controller.seekTo(0)
            Key.Escape -> when {
                panel != null -> panel = if (panel == Panel.STYLE) Panel.SUBTITLES else null
                window.fullscreen -> window.toggleFullscreen()
                window.compact -> window.toggleCompact()
                else -> onClose()
            }
            else -> return@onKeyEvent false
        }
        true
    }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(focus)
                .focusable()
                .then(keys)
                .pointerHoverIcon(if (controlsVisible || panel != null) PointerIcon.Default else PointerIcon(blankCursor))
                .onPointerEvent(PointerEventType.Move) { touched() }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    if (!locked) volumeBy(if (event.changes.first().scrollDelta.y < 0) 5 else -5)
                },
        ) {
            val height = maxHeight
            controller.frames.Surface(fit, Modifier.fillMaxSize())

            // Sous-titres d'un module, dessinés au-dessus de l'image avec le style des réglages.
            controller.cueText?.let { text ->
                val lift = if (controlsVisible && !window.compact) 150.dp else 24.dp
                SubtitleText(
                    text,
                    settings.subtitleStyle,
                    baseSize = (height.value / 26f).coerceIn(14f, 44f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = lift + height * (settings.subtitleStyle.bottomPercent / 100f))
                        .widthIn(max = 1100.dp)
                        .padding(horizontal = 24.dp),
                )
            }

            Box(
                Modifier.fillMaxSize().pointerInput(locked) {
                    detectTapGestures(
                        onTap = {
                            requestFocusSafely(focus)
                            if (locked) touched() else if (panel == null) controller.togglePlay()
                        },
                        onDoubleTap = { if (!locked) window.toggleFullscreen() },
                    )
                },
            )

            if (window.compact) {
                CompactControls(controller, controlsVisible, window)
            } else {
                androidx.compose.animation.AnimatedVisibility(controlsVisible && !locked, enter = fadeIn(), exit = fadeOut()) {
                    Box(Modifier.fillMaxSize()) {
                        Scrims()
                        TopBar(request, room != null, window, onClose, onSources = { panel = Panel.SUBTITLES }, Modifier.align(Alignment.TopStart))
                        if (controller.status !is PlayerStatus.Failed) {
                            CenterControls(controller, ::skip, Modifier.align(Alignment.Center))
                        }
                        BottomControls(
                            controller = controller,
                            scrubMs = scrubMs,
                            onScrub = { scrubMs = it; touched() },
                            inParty = room != null,
                            partyOpen = partyOpen,
                            unread = unread,
                            sleeping = sleepAt > 0,
                            window = window,
                            onParty = { partyOpen = !partyOpen },
                            onPanel = { panel = it },
                            onFit = {
                                fit = fit.next()
                                flash(fitLabelPlain(app, fit))
                            },
                            onLock = {
                                locked = true
                                panel = null
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
                if (locked) {
                    androidx.compose.animation.AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) {
                        Box(Modifier.background(P.velours.copy(alpha = 0.7f), CircleShape)) {
                            GhostButton(WmIcons.Unlock, tr("Déverrouiller", "Unlock"), { locked = false; touched() }, size = 56.dp)
                        }
                    }
                }
                PartyChips(app, controlsVisible, partyOpen, onOpenChat = { partyOpen = true }, onJoinMedia, joining, Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 12.dp))
            }

            if (controller.buffering && controller.status is PlayerStatus.Ready && (!controlsVisible || window.compact)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = P.neon, strokeWidth = 3.dp)
            }
            hud?.let { (text, detail) -> CenterHud(text, detail, Modifier.align(Alignment.Center).padding(bottom = 220.dp)) }
            scrubMs?.let { CenterHud(formatTime(it), null, Modifier.align(Alignment.Center).padding(bottom = 220.dp)) }

            val status = controller.status
            if (status !is PlayerStatus.Failed) {
                controller.torrent?.let { t ->
                    Text(
                        tr(
                            "Pairs : ${t.peers} · ${formatRate(t.downloadRate)} · ${(t.progress * 100).toInt()} %",
                            "Peers: ${t.peers} · ${formatRate(t.downloadRate)} · ${(t.progress * 100).toInt()} %",
                        ),
                        color = P.faisceau,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 170.dp)
                            .background(P.velours.copy(alpha = 0.75f), WmShape.Chip).padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            when (status) {
                PlayerStatus.Preparing -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = P.neon)
                PlayerStatus.Ready -> Unit
                is PlayerStatus.Failed -> FailureView(app, controller, status, Modifier.align(Alignment.Center))
            }

            SidePanel(visible = panel != null && !window.compact, onDismiss = { panel = null }) {
                when (panel) {
                    Panel.SUBTITLES -> {
                        PanelTitle(tr("Sous-titres", "Subtitles"), onClose = { panel = null })
                        ChoiceRow(tr("Désactivés", "Off"), null, controller.selectedSubtitle == null, controller::disableSubtitles)
                        controller.subtitleTracks.forEach { track ->
                            ChoiceRow(track.label, track.detail, controller.selectedSubtitle == track.key) { controller.selectSubtitle(track.key) }
                        }
                        DelayControls(controller.subtitleDelayMs, controller::setSubtitleDelay)
                        ChoiceRow(tr("Apparence", "Appearance"), tr("Taille, couleur, fond, hauteur", "Size, color, background, position"), false) { panel = Panel.STYLE }
                    }
                    Panel.STYLE -> {
                        PanelTitle(tr("Apparence", "Appearance"), onBack = { panel = Panel.SUBTITLES }, onClose = { panel = null })
                        SubtitleStyleEditor(settings.subtitleStyle) { changed ->
                            scope.launch { app.settings.update { it.copy(subtitleStyle = changed) } }
                        }
                    }
                    Panel.AUDIO -> {
                        PanelTitle(tr("Audio", "Audio"), onClose = { panel = null })
                        if (controller.audioTracks.size <= 1) {
                            Text(tr("Cette vidéo ne propose pas de choix.", "This video offers no choice."), color = P.poussiere, style = MaterialTheme.typography.bodyMedium)
                        }
                        controller.audioTracks.forEach { track ->
                            ChoiceRow(track.label, track.detail, controller.selectedAudio == track.key) { controller.selectAudio(track.key) }
                        }
                    }
                    Panel.PLAYBACK -> {
                        PanelTitle(tr("Lecture", "Playback"), onClose = { panel = null })
                        SectionLabel(tr("Vitesse", "Speed"), formatSpeed(controller.speed))
                        ChipChoices(
                            options = PlayerControls.SPEEDS,
                            selected = controller.speed,
                            label = { formatSpeed(it) },
                            onSelect = controller::setPlaybackSpeed,
                            enabled = room == null,
                        )
                        if (room != null) {
                            Text(
                                tr("La vitesse est bloquée pendant une séance à plusieurs pour rester ensemble.", "Speed is locked while watching together so everyone stays in sync."),
                                color = P.poussiere,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        SectionLabel(
                            tr("Minuterie de mise en veille", "Sleep timer"),
                            if (sleepAt > 0) tr("Pause dans ", "Pause in ") + formatTime((sleepAt - System.currentTimeMillis()).coerceAtLeast(0)) else null,
                        )
                        val off = tr("Aucune", "Off")
                        ChipChoices(
                            options = PlayerControls.SLEEP_MINUTES,
                            selected = sleepMinutes,
                            label = { if (it == 0) off else "$it min" },
                            onSelect = { minutes ->
                                sleepMinutes = minutes
                                sleepAt = if (minutes == 0) 0L else System.currentTimeMillis() + minutes * 60_000L
                            },
                        )
                        SectionLabel(tr("Remplissage de l’écran", "Screen fill"), fitLabel(fit))
                        ChipChoices(options = VideoFit.entries, selected = fit, label = { fitLabel(it) }, onSelect = { fit = it })
                        SectionLabel(tr("Raccourcis clavier", "Keyboard shortcuts"))
                        Text(
                            tr(
                                "Espace : lecture/pause · ← → : 10 s (Maj : 1 min) · ↑ ↓ : volume · M : muet · F ou F11 : plein écran · S : sous-titres · A : audio · G/H : décalage des sous-titres · [ ] : vitesse · Échap : quitter",
                                "Space: play/pause · ← →: 10 s (Shift: 1 min) · ↑ ↓: volume · M: mute · F or F11: full screen · S: subtitles · A: audio · G/H: subtitle delay · [ ]: speed · Esc: exit",
                            ),
                            color = P.poussiere,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    null -> Unit
                }
            }
        }
        if (room != null && partyOpen && !window.compact) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(P.loge))
            PartyPanel(app, onJoinMedia, joining, Modifier.width(380.dp), onClose = { partyOpen = false })
        }
    }
}

@Composable
private fun TopBar(
    request: PlayRequest,
    inParty: Boolean,
    window: WindowControls,
    onClose: () -> Unit,
    onSources: () -> Unit,
    modifier: Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        GhostButton(WmIcons.Back, tr("Fermer le lecteur (Échap)", "Close player (Esc)"), onClose)
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                request.title,
                style = androidx.compose.ui.text.TextStyle(fontFamily = generiqueFamily(800), fontSize = androidx.compose.ui.unit.TextUnit(34f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = P.faisceau,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val episode = request.videoId.takeIf { it != request.titleId }?.let(::episodeCode)
            if (episode != null) {
                Text(episode, style = MaterialTheme.typography.labelLarge, color = P.faisceau, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        if (!inParty) GhostButton(WmIcons.Subtitles, tr("Sous-titres (S)", "Subtitles (S)"), onSources)
        GhostButton(
            if (window.fullscreen) WmIcons.ExitFullscreen else WmIcons.Fullscreen,
            if (window.fullscreen) tr("Quitter le plein écran (F11)", "Exit full screen (F11)") else tr("Plein écran (F11)", "Full screen (F11)"),
            window::toggleFullscreen,
        )
    }
}

@Composable
private fun CenterControls(controller: PlayerController, skip: (Long) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
        GhostButton(WmIcons.Rewind, tr("Reculer de 10 secondes (←)", "Back 10 seconds (←)"), { skip(-PlayerControls.DOUBLE_TAP_SEEK_MS) }, size = 64.dp, iconSize = 34.dp)
        Box(
            Modifier
                .size(88.dp)
                .background(P.faisceau, CircleShape)
                .then(Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (controller.buffering && controller.wantsPlay) {
                CircularProgressIndicator(Modifier.size(40.dp), color = P.velours, strokeWidth = 3.dp)
            }
            Box(
                Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { controller.togglePlay() } },
                contentAlignment = Alignment.Center,
            ) {
                if (!(controller.buffering && controller.wantsPlay)) {
                    Icon(
                        if (controller.wantsPlay && !controller.ended) WmIcons.PauseFilled else WmIcons.PlayFilled,
                        if (controller.wantsPlay) tr("Pause (Espace)", "Pause (Space)") else tr("Lecture (Espace)", "Play (Space)"),
                        tint = P.velours,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
        GhostButton(WmIcons.Forward, tr("Avancer de 10 secondes (→)", "Forward 10 seconds (→)"), { skip(PlayerControls.DOUBLE_TAP_SEEK_MS) }, size = 64.dp, iconSize = 34.dp)
    }
}

@Composable
private fun BottomControls(
    controller: PlayerController,
    scrubMs: Long?,
    onScrub: (Long?) -> Unit,
    inParty: Boolean,
    partyOpen: Boolean,
    unread: Int,
    sleeping: Boolean,
    window: WindowControls,
    onParty: () -> Unit,
    onPanel: (Panel) -> Unit,
    onFit: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, bottom = 16.dp)) {
        SeekBar(controller.positionMs, controller.durationMs, onScrub, controller::seekTo, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(scrubMs ?: controller.positionMs), style = MaterialTheme.typography.labelLarge, color = P.faisceau)
            Spacer(Modifier.weight(1f))
            Text(formatTime(controller.durationMs), style = MaterialTheme.typography.labelLarge, color = P.faisceau)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            VolumeControl(controller.volume, controller.muted, controller::changeVolume) { controller.setMute(!controller.muted) }
            Spacer(Modifier.weight(1f))
            if (inParty) {
                GhostButton(
                    WmIcons.Chat,
                    tr("Salle : chat et vocal", "Room: chat and voice"),
                    onParty,
                    badge = unread.takeIf { it > 0 && !partyOpen }?.let { if (it > 9) "9+" else "$it" },
                    active = partyOpen,
                )
            }
            GhostButton(WmIcons.Subtitles, tr("Sous-titres (S)", "Subtitles (S)"), { onPanel(Panel.SUBTITLES) })
            GhostButton(WmIcons.Audio, tr("Audio (A)", "Audio (A)"), { onPanel(Panel.AUDIO) })
            GhostButton(
                WmIcons.Speed,
                tr("Lecture : vitesse, minuterie", "Playback: speed, sleep timer"),
                { onPanel(Panel.PLAYBACK) },
                badge = when {
                    sleeping -> "z"
                    controller.speed != 1f -> formatSpeed(controller.speed)
                    else -> null
                },
            )
            GhostButton(WmIcons.Fit, tr("Remplissage de l’écran", "Screen fill"), onFit)
            GhostButton(WmIcons.Lock, tr("Verrouiller les commandes", "Lock controls"), onLock)
            GhostButton(WmIcons.Pip, tr("Mini-lecteur au premier plan", "Mini player on top"), window::toggleCompact)
            GhostButton(
                if (window.fullscreen) WmIcons.ExitFullscreen else WmIcons.Fullscreen,
                if (window.fullscreen) tr("Quitter le plein écran (F11)", "Exit full screen (F11)") else tr("Plein écran (F11)", "Full screen (F11)"),
                window::toggleFullscreen,
            )
        }
    }
}

/** Mini-lecteur : image seule, deux boutons au survol ; la fenêtre se déplace en glissant l'image. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CompactControls(controller: PlayerController, visible: Boolean, window: WindowControls) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
        Row(
            Modifier.padding(8.dp).background(P.velours.copy(alpha = 0.75f), WmShape.Field).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton(
                if (controller.wantsPlay) WmIcons.PauseFilled else WmIcons.PlayFilled,
                if (controller.wantsPlay) tr("Pause", "Pause") else tr("Lecture", "Play"),
                controller::togglePlay,
                size = 40.dp,
                iconSize = 20.dp,
            )
            Text(formatTime(controller.positionMs), style = MaterialTheme.typography.labelMedium, color = P.faisceau, modifier = Modifier.padding(horizontal = 6.dp))
            GhostButton(WmIcons.Fullscreen, tr("Agrandir le lecteur", "Restore player"), window::toggleCompact, size = 40.dp, iconSize = 20.dp)
        }
    }
}

@Composable
private fun FailureView(app: DesktopApp, controller: PlayerController, status: PlayerStatus.Failed, modifier: Modifier) {
    Column(
        modifier.widthIn(max = 520.dp).background(P.velours.copy(alpha = 0.85f), WmShape.Field).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            when (status.code) {
                PlayerController.RELAY_UNAVAILABLE -> tr("Le relais local ne répond pas.", "The local relay is not responding.")
                PlayerController.P2P_UNAVAILABLE -> tr("La lecture pair-à-pair n’est pas disponible sur cet ordinateur.", "Peer-to-peer playback is not available on this computer.")
                PlayerController.VLC_MISSING -> tr(
                    "Le moteur vidéo (libVLC) est introuvable. Réinstallez WatchMate, ou installez VLC 64 bits.",
                    "The video engine (libVLC) was not found. Reinstall WatchMate, or install 64-bit VLC.",
                )
                else -> status.detail ?: tr("Cette source ne peut pas être lue.", "This source cannot be played.")
            },
            color = P.faisceau,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(status.code, color = P.poussiere, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.code != PlayerController.VLC_MISSING) NeonButton(tr("Réessayer", "Retry"), controller::retry)
            OutlineButton(tr("Ouvrir dans un autre lecteur", "Open in another player"), {
                app.mainScope.launch { openExternally(app, controller.request) }
            })
        }
    }
}

/** Puces de la salle dans le lecteur : membres, attente, ce que les autres viennent de faire, dernier message. */
@Composable
private fun PartyChips(
    app: DesktopApp,
    controlsVisible: Boolean,
    chatOpen: Boolean,
    onOpenChat: () -> Unit,
    onJoinMedia: () -> Unit,
    joining: Boolean,
    modifier: Modifier,
) {
    val state by app.party.state.collectAsState()
    val lines by app.party.chat.collectAsState()
    val s = state as? PartyState.InRoom ?: return
    val alreadyThere = remember { lines.lastOrNull() }
    var incoming by remember { mutableStateOf<PartyChatLine?>(null) }
    val last = lines.lastOrNull()
    LaunchedEffect(last, chatOpen) {
        if (chatOpen || last == null || last === alreadyThere || last.from == s.selfId) {
            incoming = null
            return@LaunchedEffect
        }
        incoming = last
        delay(5_000)
        incoming = null
    }
    var notice by remember { mutableStateOf<PartyNotice?>(null) }
    LaunchedEffect(Unit) { app.party.notices.collect { notice = it } }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_000)
            notice = null
        }
    }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (controlsVisible) Chip(membersCount(s.members.size) + " · " + s.members.joinToString(", ") { it.name })
        if (s.waitingFor.isNotEmpty()) Chip(tr("En attente de ", "Waiting for ") + s.waitingFor.joinToString(", ") { s.nameOf(it) } + "…")
        notice?.let { Chip(noticeText(it, s)) }
        incoming?.let { line ->
            Text(
                "${s.nameOf(line.from)} · ${line.text}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(Color.Black.copy(alpha = 0.7f), WmShape.Chip)
                    .pointerInput(Unit) { detectTapGestures { onOpenChat() } }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        val timeline = s.timeline
        if (timeline != null && !s.synced) {
            Row(
                Modifier.background(Color.Black.copy(alpha = 0.7f), WmShape.Chip).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    tr("${s.nameOf(timeline.by)} regarde « ${mediaLabel(timeline.media)} »", "${s.nameOf(timeline.by)} is watching “${mediaLabel(timeline.media)}”"),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                NeonButton(if (joining) tr("Recherche…", "Searching…") else tr("Regarder aussi", "Watch too"), onJoinMedia, enabled = !joining, height = 36.dp)
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), WmShape.Chip).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private fun requestFocusSafely(focus: FocusRequester) {
    runCatching { focus.requestFocus() }
}

private fun tr2(app: DesktopApp, fr: String, en: String) = if (app.french) fr else en

private fun fitLabelPlain(app: DesktopApp, fit: VideoFit): String = when (fit) {
    VideoFit.FIT -> tr2(app, "Image entière", "Fit")
    VideoFit.ZOOM -> tr2(app, "Remplir (recadrer)", "Fill (crop)")
    VideoFit.STRETCH -> tr2(app, "Étirer", "Stretch")
}

private fun formatRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000 -> "%.1f Mo/s".format(bytesPerSecond / 1_000_000.0)
    else -> "${bytesPerSecond / 1_000} ko/s"
}

/** Curseur caché quand les commandes le sont, comme dans tout lecteur vidéo. */
private val blankCursor: Cursor = java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
    java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB),
    java.awt.Point(0, 0),
    "blank",
)
