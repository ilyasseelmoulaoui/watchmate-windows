package app.agregateur

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.agregateur.core.catalog.normalizeQuery
import app.agregateur.core.settings.ThemeMode
import app.agregateur.party.PartyAutoJoin
import app.agregateur.party.PartyInviteDialogs
import app.agregateur.party.PartyPanel
import app.agregateur.party.PartyState
import app.agregateur.player.PlayRequest
import app.agregateur.player.PlayerScreen
import app.agregateur.player.WindowControls
import app.agregateur.screens.ExploreScreen
import app.agregateur.screens.HomeScreen
import app.agregateur.screens.LibraryScreen
import app.agregateur.screens.ModulesScreen
import app.agregateur.screens.PasswordResetDialog
import app.agregateur.screens.SearchScreen
import app.agregateur.screens.SettingsScreen
import app.agregateur.screens.TitleScreen
import app.agregateur.ui.FocusVisibility
import app.agregateur.ui.LocalFrench
import app.agregateur.ui.LocalImageLoader
import app.agregateur.ui.NavRail
import app.agregateur.ui.ResizeEdges
import app.agregateur.ui.TitleBar
import app.agregateur.ui.episodeCode
import app.agregateur.ui.theme.WatchMateTheme
import app.agregateur.ui.theme.WmIcons
import app.agregateur.ui.theme.WmShape
import app.agregateur.ui.theme.wm
import app.agregateur.ui.tr
import app.agregateur.ui.wmClickable
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main(args: Array<String>) {
    val instance = SingleInstance.claim(args) ?: return
    application {
        val app = remember { DesktopApp().also { it.start() } }
        val nav = remember { AppNav() }
        val settings by app.settings.settings.collectAsState()
        val french = when (settings.appLanguage) {
            "en" -> false
            "fr" -> true
            else -> Locale.getDefault().language.startsWith("fr")
        }
        app.french = french
        LaunchedEffect(instance) {
            DeepLinks.register()
            instance.links.collect { DeepLinks.handle(app, it) }
        }
        val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp), position = WindowPosition(Alignment.Center))
        fun quit() {
            app.close()
            exitApplication()
        }
        Window(
            onCloseRequest = ::quit,
            title = "WatchMate",
            state = windowState,
            undecorated = true,
            onPreviewKeyEvent = { event ->
                if (event.key == Key.Tab) FocusVisibility.keyboard = true
                when {
                    event.type != KeyEventType.KeyDown -> false
                    event.isCtrlPressed && event.key == Key.E && nav.playing == null -> {
                        nav.focusSearch()
                        true
                    }
                    event.isAltPressed && event.key == Key.DirectionLeft -> nav.back()
                    else -> false
                }
            },
        ) {
            val controls = rememberWindowControls(windowState)
            LaunchedEffect(controls.compact) {
                window.minimumSize = if (controls.compact) Dimension(320, 180) else Dimension(960, 600)
                window.isAlwaysOnTop = controls.compact
            }
            LaunchedEffect(nav.playing == null) { if (nav.playing == null) controls.restore() }
            DisposableEffect(Unit) {
                val listener = object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent?) {
                        app.sync.onForeground()
                        app.party.onForeground()
                    }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            WatchMateTheme(settings.themeMode) {
                CompositionLocalProvider(
                    LocalImageLoader provides app.images,
                    LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                    LocalFrench provides french,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.type == PointerEventType.Press) FocusVisibility.keyboard = false
                                    }
                                }
                            },
                    ) {
                        AppShell(app, nav, french, controls, ::quit)
                        if (!controls.fullscreen && !controls.compact) ResizeEdges(windowState)
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameWindowScope.AppShell(app: DesktopApp, nav: AppNav, french: Boolean, controls: AppWindowControls, quit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val partyState by app.party.state.collectAsState()
    var joining by remember { mutableStateOf(false) }
    var pendingP2p by remember { mutableStateOf<PlayRequest?>(null) }
    var autoJoinedKey by remember { mutableStateOf<String?>(null) }

    fun joinWatching() {
        val media = app.party.joinableMedia() ?: return
        if (joining || pendingP2p != null) return
        joining = true
        scope.launch {
            val start = app.party.startPositionFor(media) ?: 0L
            when (val result = PartyAutoJoin.resolve(media, app.modules, app.titles, start)) {
                is PartyAutoJoin.Result.Ready -> {
                    if (result.needsP2pNotice && !app.settings.settings.value.p2pNoticeAccepted) {
                        pendingP2p = result.request
                    } else {
                        nav.play(result.request)
                    }
                }
                PartyAutoJoin.Result.NoSource -> {
                    nav.stopPlaying()
                    nav.open(TitleRef(media.type, media.id, media.moduleUrl, media.videoId))
                    app.notify(
                        "Aucune source jouable sur cet ordinateur pour ce que regarde la salle. Choisissez-en une sur la fiche.",
                        "No playable source on this computer for what the room is watching. Pick one on the title page.",
                    )
                }
            }
            joining = false
        }
    }

    LaunchedEffect(partyState, joining, pendingP2p) {
        if (partyState !is PartyState.InRoom) {
            autoJoinedKey = null
            return@LaunchedEffect
        }
        val media = app.party.joinableMedia() ?: return@LaunchedEffect
        if (joining || pendingP2p != null) return@LaunchedEffect
        val key = "${media.type}:${media.id}:${media.videoId}"
        if (autoJoinedKey == key) return@LaunchedEffect
        autoJoinedKey = key
        joinWatching()
    }

    pendingP2p?.let { request ->
        P2pNoticeDialog(
            onAccept = {
                pendingP2p = null
                app.appScope.launch { app.settings.update { it.copy(p2pNoticeAccepted = true) } }
                nav.play(request)
            },
            onDismiss = { pendingP2p = null },
        )
    }
    PartyInviteDialogs(app)
    PasswordResetDialog(app)

    Box(Modifier.fillMaxSize()) {
        val playing = nav.playing
        Column(Modifier.fillMaxSize()) {
            if (!controls.fullscreen && !controls.compact) {
                TitleBar(
                    state = controls.state,
                    french = french,
                    onBack = if (nav.canGoBack) ({ nav.back() }) else null,
                    onClose = quit,
                ) { if (playing == null) SearchBox(nav, french) else PlayingTitle(playing) }
            }
            if (playing != null) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    WatchMateTheme(ThemeMode.DARK) {
                        if (controls.compact) {
                            WindowDraggableArea(Modifier.fillMaxSize()) {
                                PlayerScreen(app, playing, controls, onClose = nav::stopPlaying, onJoinMedia = ::joinWatching, joining = joining)
                            }
                        } else {
                            PlayerScreen(app, playing, controls, onClose = nav::stopPlaying, onJoinMedia = ::joinWatching, joining = joining)
                        }
                    }
                }
            } else {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    NavRail(nav.tab, french, nav::select)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Content(app, nav, french)
                        PartyBubble(app, partyState, nav.partyOpen, { nav.partyOpen = !nav.partyOpen }, Modifier.align(Alignment.BottomEnd).padding(24.dp))
                    }
                    if (nav.partyOpen) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
                        PartyPanel(app, ::joinWatching, joining, Modifier.width(380.dp), onClose = { nav.partyOpen = false })
                    }
                }
            }
        }
        Notices(app, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
    }
}

@Composable
private fun PlayingTitle(request: PlayRequest) {
    Text(
        listOfNotNull(request.title, request.videoId.takeIf { it != request.titleId }?.let(::episodeCode)).joinToString(" · "),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun PartyBubble(app: DesktopApp, state: PartyState, open: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    if (open) return
    val room = state as? PartyState.InRoom
    val failed = state is PartyState.Failed
    val needsJoin = room?.let { it.timeline != null && !it.synced } == true
    val synced = room?.let { it.connected && it.synced } == true
    val container = when {
        failed -> MaterialTheme.colorScheme.error
        synced -> MaterialTheme.wm.synchro
        else -> MaterialTheme.colorScheme.primary
    }
    val content = when {
        failed -> MaterialTheme.colorScheme.onError
        synced -> MaterialTheme.colorScheme.background
        else -> MaterialTheme.colorScheme.onPrimary
    }
    val label = tr("Regarder ensemble", "Watch together")
    Box(modifier) {
        Box(
            Modifier
                .size(56.dp)
                .background(container, WmShape.Feature)
                .wmClickable(WmShape.Feature, hover = Color.Transparent, onClick = onToggle)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (room != null) WmIcons.Pulse else WmIcons.People,
                null,
                tint = content,
                modifier = Modifier.size(26.dp),
            )
        }
        if (needsJoin) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(14.dp)
                    .background(MaterialTheme.colorScheme.background, CircleShape)
                    .padding(2.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

@Composable
private fun Notices(app: DesktopApp, modifier: Modifier) {
    var message by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        app.notices.collect {
            message = it
            tick++
        }
    }
    LaunchedEffect(tick) {
        if (message != null) {
            delay(3_500)
            message = null
        }
    }
    AnimatedVisibility(message != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            message.orEmpty(),
            Modifier
                .widthIn(max = 480.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, WmShape.Chip)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SearchBox(nav: AppNav, french: Boolean) {
    var text by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onBackground, fontSize = 14.sp),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(colors.surfaceVariant, WmShape.Chip)
            .padding(horizontal = 12.dp)
            .focusRequester(nav.searchFocus)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                    normalizeQuery(text)?.let { nav.search(it) }
                    true
                } else {
                    false
                }
            },
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    Text(
                        if (french) "Rechercher (Ctrl+E)" else "Search (Ctrl+E)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun Content(app: DesktopApp, nav: AppNav, french: Boolean) {
    val title = nav.title
    val search = nav.search
    when {
        title != null -> TitleScreen(app, title, french, onPlay = nav::play)
        search != null -> SearchScreen(app, search, french, onOpenTitle = nav::open)
        else -> when (nav.tab) {
            Tab.Home -> HomeScreen(app, french, onOpenTitle = nav::open)
            Tab.Explore -> ExploreScreen(app, french, onOpenTitle = nav::open)
            Tab.Library -> LibraryScreen(app, french, onOpenTitle = nav::open)
            Tab.Modules -> ModulesScreen(app, french)
            Tab.Settings -> SettingsScreen(app, french)
        }
    }
}

@Composable
fun P2pNoticeDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Lecture pair-à-pair", "Peer-to-peer playback")) },
        text = {
            Text(
                tr(
                    "Cette source est un torrent : pendant la lecture, l’application reçoit la vidéo d’autres utilisateurs et leur en renvoie des morceaux. Votre adresse IP est visible de ces utilisateurs. Vous êtes responsable de ce que vous lisez.",
                    "This source is a torrent: while it plays, the app receives the video from other users and sends pieces back to them. Your IP address is visible to those users. You are responsible for what you play.",
                ),
            )
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(tr("Continuer", "Continue")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler", "Cancel")) } },
    )
}

@Stable
class AppWindowControls(val state: WindowState) : WindowControls {
    private var beforeFullscreen: WindowPlacement? = null
    private var beforeCompact: DpSize? = null
    private var beforeCompactPlacement: WindowPlacement? = null

    override var fullscreen by mutableStateOf(false)
        private set
    override var compact by mutableStateOf(false)
        private set

    override fun toggleFullscreen() {
        if (compact) return
        if (fullscreen) {
            state.placement = beforeFullscreen ?: WindowPlacement.Floating
            beforeFullscreen = null
            fullscreen = false
        } else {
            beforeFullscreen = state.placement
            state.placement = WindowPlacement.Fullscreen
            fullscreen = true
        }
    }

    override fun toggleCompact() {
        if (fullscreen) toggleFullscreen()
        if (compact) {
            beforeCompact?.let { state.size = it }
            state.placement = beforeCompactPlacement ?: WindowPlacement.Floating
            beforeCompact = null
            beforeCompactPlacement = null
            compact = false
        } else {
            beforeCompact = state.size
            beforeCompactPlacement = state.placement
            state.placement = WindowPlacement.Floating
            state.size = DpSize(420.dp, 240.dp)
            compact = true
        }
    }

    fun restore() {
        if (fullscreen) toggleFullscreen()
        if (compact) toggleCompact()
    }
}

@Composable
private fun rememberWindowControls(state: WindowState): AppWindowControls = remember(state) { AppWindowControls(state) }
