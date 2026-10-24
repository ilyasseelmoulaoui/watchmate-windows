package app.agregateur.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.agregateur.DesktopApp
import app.agregateur.P2pNoticeDialog
import app.agregateur.core.library.resumePositionMs
import app.agregateur.core.playback.RelayTarget
import app.agregateur.party.PartyState
import app.agregateur.player.PlayRequest
import app.agregateur.player.formatTime
import app.agregateur.player.openExternally
import androidx.compose.ui.semantics.contentDescription
import app.agregateur.TitleRef
import app.agregateur.core.addon.AddonError
import app.agregateur.core.addon.AddonResult
import app.agregateur.core.library.LibraryRepository
import app.agregateur.core.library.TitleSnapshot
import app.agregateur.core.library.find
import app.agregateur.core.model.Meta
import app.agregateur.core.model.Stream
import app.agregateur.core.model.Video
import app.agregateur.core.modules.InstalledModule
import app.agregateur.core.modules.ModuleRepository
import app.agregateur.core.playback.Route
import app.agregateur.core.playback.qualityPenalty
import app.agregateur.core.playback.route
import app.agregateur.core.playback.traits
import app.agregateur.core.title.MetaOutcome
import app.agregateur.core.title.ModuleStreams
import app.agregateur.core.title.TitleService
import app.agregateur.core.title.seasons
import app.agregateur.ui.ErrorLine
import app.agregateur.ui.MutedText
import app.agregateur.ui.NeonButton
import app.agregateur.ui.PosterArt
import app.agregateur.ui.ProgressLine
import app.agregateur.ui.RemoteImage
import app.agregateur.ui.SquareIconButton
import app.agregateur.ui.addonErrorMessage
import app.agregateur.ui.episodeCode
import app.agregateur.ui.openUrl
import app.agregateur.ui.seasonLabel
import app.agregateur.ui.sourceKindLabel
import app.agregateur.ui.theme.WmIcons
import app.agregateur.ui.theme.WmShape
import app.agregateur.ui.theme.WmType
import app.agregateur.ui.typeLabel
import app.agregateur.ui.wmClickable
import app.agregateur.ui.youtubeWatchUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private sealed interface MetaState {
    data object Loading : MetaState
    data class Loaded(val meta: Meta, val moduleName: String) : MetaState
    data object NotFound : MetaState
    data class Failed(val error: AddonError) : MetaState
}

private data class SourceItem(val stream: Stream, val route: Route)

private sealed interface GroupState {
    data object Loading : GroupState
    data class Loaded(val sources: List<SourceItem>) : GroupState
    data class Failed(val error: AddonError) : GroupState
}

private data class SourceGroup(val moduleUrl: String, val moduleName: String, val state: GroupState)

private data class TitleUiState(
    val meta: MetaState = MetaState.Loading,
    val videoId: String? = null,
    val groups: List<SourceGroup> = emptyList(),
)

private class TitleController(
    private val modules: ModuleRepository,
    private val service: TitleService,
    private val ref: TitleRef,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(TitleUiState())
    val state: StateFlow<TitleUiState> = _state.asStateFlow()
    private var sourcesJob: Job? = null

    fun loadMeta() {
        _state.update { it.copy(meta = MetaState.Loading) }
        scope.launch {
            val outcome = service.meta(modules.modules.value, ref.type, ref.id, ref.moduleUrl)
            val meta = when (outcome) {
                is MetaOutcome.Loaded -> MetaState.Loaded(outcome.meta, outcome.moduleName)
                MetaOutcome.NotFound -> MetaState.NotFound
                is MetaOutcome.Failed -> MetaState.Failed(outcome.error)
            }
            _state.update { it.copy(meta = meta) }
            val loaded = (outcome as? MetaOutcome.Loaded)?.meta
            val noEpisodes = loaded?.videos?.isEmpty() ?: (ref.type == "movie")
            val resumeVideo = library.entries.value.find(ref.type, ref.id)?.progress?.videoId
            when {
                noEpisodes -> selectVideo(ref.id)
                ref.videoId != null && loaded?.videos?.any { it.id == ref.videoId } == true -> selectVideo(ref.videoId)
                resumeVideo != null && loaded?.videos?.any { it.id == resumeVideo } == true -> selectVideo(resumeVideo)
            }
        }
    }

    fun selectVideo(videoId: String) {
        sourcesJob?.cancel()
        val installed = modules.modules.value
        val targets = service.streamModules(installed, ref.type, videoId)
        _state.update {
            it.copy(
                videoId = videoId,
                groups = targets.map { m -> SourceGroup(m.manifestUrl, m.manifest.name, GroupState.Loading) },
            )
        }
        sourcesJob = scope.launch {
            targets.forEach { module -> launch { apply(service.streamsOf(module, ref.type, videoId)) } }
        }
    }

    fun retry(moduleUrl: String) {
        val videoId = _state.value.videoId ?: return
        val module: InstalledModule = modules.modules.value.firstOrNull { it.manifestUrl == moduleUrl } ?: return
        setGroup(moduleUrl) { it.copy(state = GroupState.Loading) }
        scope.launch { apply(service.streamsOf(module, ref.type, videoId)) }
    }

    private fun apply(result: ModuleStreams) {
        val state = when (val r = result.result) {
            is AddonResult.Success -> GroupState.Loaded(
                r.value.map { SourceItem(it, route(it)) }
                    .sortedWith(
                        compareBy(
                            { item ->
                                when (item.route) {
                                    is Route.PlayDirect, is Route.PlayPlatform, is Route.OpenExternalLink -> 0
                                    is Route.PlayViaRelay -> 1
                                    is Route.Insecure, is Route.Unplayable -> 2
                                }
                            },
                            { it.stream.traits().qualityPenalty() },
                        ),
                    ),
            )
            is AddonResult.Failure -> GroupState.Failed(r.error)
        }
        setGroup(result.moduleUrl) { it.copy(state = state) }
    }

    private fun setGroup(moduleUrl: String, change: (SourceGroup) -> SourceGroup) = _state.update { ui ->
        ui.copy(groups = ui.groups.map { if (it.moduleUrl == moduleUrl) change(it) else it })
    }
}

@Composable
fun TitleScreen(app: DesktopApp, ref: TitleRef, french: Boolean, onPlay: (PlayRequest) -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember(ref) {
        TitleController(app.modules, app.titles, ref, app.library, scope)
    }
    LaunchedEffect(controller) { controller.loadMeta() }
    val state by controller.state.collectAsState()
    val library by app.library.entries.collectAsState()
    val entry = library.find(ref.type, ref.id)
    val saved = entry?.saved == true
    var selectedSeason by remember(ref) { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val loadedMeta = (state.meta as? MetaState.Loaded)?.meta
    val titleText = loadedMeta?.name?.ifBlank { null } ?: entry?.name ?: ref.id

    val resumeMs = state.videoId?.let { entry.resumePositionMs(it) } ?: 0L
    var pendingP2p by remember { mutableStateOf<(() -> Unit)?>(null) }

    /** Avant la première lecture pair-à-pair, l'utilisateur lit ce qu'elle implique. */
    fun afterP2pNotice(route: Route, action: () -> Unit) {
        val torrent = (route as? Route.PlayViaRelay)?.target is RelayTarget.Torrent
        if (torrent && !app.settings.settings.value.p2pNoticeAccepted) pendingP2p = action else action()
    }

    fun requestFor(item: SourceItem, url: String, headers: Map<String, String>, target: RelayTarget?): PlayRequest? {
        val video = state.videoId ?: return null
        val request = PlayRequest(
            url = url,
            headers = headers,
            type = ref.type,
            videoId = video,
            stream = item.stream,
            title = titleText,
            titleId = ref.id,
            poster = loadedMeta?.poster ?: entry?.poster,
            moduleUrl = ref.moduleUrl,
            startPositionMs = resumeMs,
            relayTarget = target,
        )
        // Dans une salle qui regarde ce même épisode : partir de la position de la salle.
        return request.copy(startPositionMs = app.party.startPositionFor(request.partyMedia) ?: resumeMs)
    }

    fun openSource(item: SourceItem, external: Boolean = app.settings.settings.value.preferExternalPlayer) {
        status = null
        when (val r = item.route) {
            is Route.PlayDirect -> requestFor(item, r.url, r.headers, null)?.let { request ->
                if (external) scope.launch { openExternally(app, request) } else onPlay(request)
            }
            is Route.PlayViaRelay -> afterP2pNotice(r) {
                requestFor(item, "", emptyMap(), r.target)?.let { request ->
                    if (external) scope.launch { openExternally(app, request) } else onPlay(request)
                }
            }
            is Route.PlayPlatform -> {
                if (!openUrl(youtubeWatchUrl(r.videoId))) {
                    status = if (french) "Impossible d’ouvrir la plateforme." else "Could not open platform."
                }
            }
            is Route.OpenExternalLink -> {
                if (!openUrl(r.url)) {
                    status = if (french) "Impossible d’ouvrir le lien." else "Could not open link."
                }
            }
            is Route.Insecure, is Route.Unplayable -> Unit
        }
    }

    pendingP2p?.let { action ->
        P2pNoticeDialog(
            onAccept = {
                pendingP2p = null
                app.appScope.launch { app.settings.update { it.copy(p2pNoticeAccepted = true) } }
                action()
            },
            onDismiss = { pendingP2p = null },
        )
    }

    fun toggleLibrary() {
        scope.launch {
            val snapshot = TitleSnapshot(ref.type, ref.id, titleText, loadedMeta?.poster ?: entry?.poster, ref.moduleUrl)
            val ok = if (saved) app.library.remove(ref.type, ref.id) else app.library.add(snapshot)
            if (!ok) status = if (french) "Échec bibliothèque." else "Library update failed."
        }
    }

    // « Lire » : d'abord ce que le lecteur intégré lit (direct, puis relais), un lien externe seulement à défaut.
    val firstPlayable = state.groups.asSequence()
        .mapNotNull { it.state as? GroupState.Loaded }
        .flatMap { it.sources }
        .filter { it.route.playable }
        .sortedBy {
            when (it.route) {
                is Route.PlayDirect -> 0
                is Route.PlayViaRelay -> 1
                else -> 2
            }
        }
        .firstOrNull()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero(loadedMeta?.background ?: loadedMeta?.poster ?: entry?.poster, "${ref.type}:${ref.id}")
        Column(Modifier.padding(start = 40.dp, end = 40.dp, bottom = 48.dp)) {
            Text(
                titleText,
                style = titleStyle(titleText),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(y = (-84).dp),
            )
            Column(Modifier.offset(y = (-72).dp)) {
                when (val meta = state.meta) {
                    MetaState.Loading -> MutedText(if (french) "Chargement…" else "Loading…")
                    MetaState.NotFound -> MutedText(if (french) "Fiche introuvable." else "Title not found.")
                    is MetaState.Failed -> ErrorLine(addonErrorMessage(meta.error, french), french, onRetry = controller::loadMeta)
                    is MetaState.Loaded -> Overview(
                        meta = meta.meta,
                        moduleName = meta.moduleName,
                        french = french,
                        playLabel = playLabel(state.videoId, ref, french),
                        canPlay = firstPlayable != null,
                        saved = saved,
                        onPlay = { firstPlayable?.let { openSource(it) } },
                        onToggleLibrary = ::toggleLibrary,
                    )
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
                }
                if (resumeMs > 0) {
                    MutedText(if (french) "Reprise à ${formatTime(resumeMs)}" else "Resume at ${formatTime(resumeMs)}", Modifier.padding(top = 12.dp))
                }
                PartyTitleHint(app, ref, state.videoId, french)

                val seasons = loadedMeta?.seasons().orEmpty()
                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 40.dp)) {
                    val wide = maxWidth >= 900.dp
                    val episodes: (@Composable (Modifier) -> Unit)? = if (seasons.isNotEmpty()) {
                        { modifier ->
                            val shownSeason = selectedSeason
                                ?: seasons.firstOrNull { s -> s.episodes.any { it.id == state.videoId } }?.number
                            val current = seasons.firstOrNull { it.number == shownSeason } ?: seasons.first()
                            Column(modifier) {
                                SeasonTabs(seasons.map { it.number }, current.number, french) { selectedSeason = it }
                                Column(Modifier.padding(top = 8.dp)) {
                                    current.episodes.forEach { video ->
                                        EpisodeRow(
                                            video = video,
                                            selected = video.id == state.videoId,
                                            progress = entry?.progress?.takeIf { it.videoId == video.id }?.ratio,
                                            french = french,
                                            onClick = { controller.selectVideo(video.id) },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        null
                    }
                    val sources: @Composable (Modifier) -> Unit = { modifier ->
                        SourcesPanel(state, seasons.isNotEmpty(), french, modifier, onRetry = controller::retry, onOpen = { openSource(it) }, onOther = { openSource(it, external = true) })
                    }
                    when {
                        episodes == null -> sources(Modifier.widthIn(max = 720.dp))
                        wide -> Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                            episodes(Modifier.weight(3f))
                            sources(Modifier.weight(2f))
                        }
                        else -> Column(verticalArrangement = Arrangement.spacedBy(40.dp)) {
                            episodes(Modifier.fillMaxWidth())
                            sources(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

private val Route.playable: Boolean get() = this !is Route.Insecure && this !is Route.Unplayable

private fun playLabel(videoId: String?, ref: TitleRef, french: Boolean): String {
    val code = videoId?.takeIf { it != ref.id }?.let(::episodeCode)
    val base = if (french) "Lire" else "Play"
    return if (code != null) "$base $code" else base
}

/** Le titre de 160 sp tient pour un nom court ; on descend d'un cran pour les noms longs. */
private fun titleStyle(name: String) = when {
    name.length <= 14 -> WmType.TitreFiche
    name.length <= 26 -> WmType.TitreFicheMoyen
    else -> WmType.TitreFicheLong
}

@Composable
private fun Hero(image: String?, seed: String) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().height(420.dp)) {
        RemoteImage(image, null, Modifier.fillMaxSize()) { PosterArt("", seed) }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(160.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, background))),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Overview(
    meta: Meta,
    moduleName: String,
    french: Boolean,
    playLabel: String,
    canPlay: Boolean,
    saved: Boolean,
    onPlay: () -> Unit,
    onToggleLibrary: () -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val details = listOfNotNull(
                when (meta.type) {
                    "movie" -> "Film"
                    "series" -> if (french) "Série" else "Series"
                    else -> typeLabel(meta.type, french)
                },
                meta.releaseInfo,
                meta.runtime,
                meta.genres.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", "),
                if (french) "fiche $moduleName" else "from $moduleName",
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            meta.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonButton(playLabel, onPlay, enabled = canPlay, icon = WmIcons.PlayFilled, height = 52.dp)
            SquareIconButton(
                if (saved) WmIcons.Check else WmIcons.Add,
                if (saved) {
                    if (french) "Retirer de ma liste" else "Remove from my list"
                } else {
                    if (french) "Ajouter à ma liste" else "Add to my list"
                },
                onToggleLibrary,
            )
        }
    }
}

@Composable
private fun SeasonTabs(numbers: List<Int?>, current: Int?, french: Boolean, onSelect: (Int?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            numbers.forEach { number ->
                val active = number == current
                Box(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .height(48.dp)
                        .wmClickable(hover = Color.Transparent, role = Role.Tab) { onSelect(number) }
                        .semantics { selected = active },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        seasonLabel(number, french),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (active) colors.onBackground else colors.onSurfaceVariant,
                    )
                    if (active) {
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(colors.primary))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.surfaceVariant))
    }
}

@Composable
private fun EpisodeRow(video: Video, selected: Boolean, progress: Float?, french: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(colors.surfaceVariant, WmShape.Chip) else Modifier)
            .wmClickable(WmShape.Chip, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(160.dp).height(90.dp).clip(WmShape.Poster).background(MaterialTheme.colorScheme.outlineVariant)) {
            RemoteImage(video.thumbnail, null, Modifier.fillMaxSize())
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth(), track = Color.Transparent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val number = video.episode?.let { "$it. " } ?: ""
            Text("$number${video.displayTitle ?: video.id}", style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
            video.released?.take(10)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            if (selected) {
                video.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 560.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SourcesPanel(
    state: TitleUiState,
    hasEpisodes: Boolean,
    french: Boolean,
    modifier: Modifier,
    onRetry: (String) -> Unit,
    onOpen: (SourceItem) -> Unit,
    onOther: (SourceItem) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        val code = state.videoId?.let(::episodeCode)
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                when {
                    code != null -> if (french) "Sources pour $code" else "Sources for $code"
                    else -> "Sources"
                },
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.surfaceVariant))
        Column(Modifier.padding(top = 8.dp)) {
            when {
                state.videoId == null -> MutedText(
                    if (hasEpisodes) {
                        if (french) "Choisissez un épisode." else "Pick an episode."
                    } else {
                        if (french) "Chargement…" else "Loading…"
                    },
                    Modifier.padding(12.dp),
                )
                state.groups.isEmpty() -> MutedText(
                    if (french) {
                        "Aucun module ne propose de sources pour cette vidéo."
                    } else {
                        "No installed module offers sources for this video."
                    },
                    Modifier.padding(12.dp),
                )
                else -> state.groups.forEach { group ->
                    when (val groupState = group.state) {
                        GroupState.Loading -> MutedText(
                            if (french) "${group.moduleName} · chargement…" else "${group.moduleName} · loading…",
                            Modifier.padding(12.dp),
                        )
                        is GroupState.Failed -> ErrorLine(
                            "${group.moduleName} · ${addonErrorMessage(groupState.error, french)}",
                            french,
                            onRetry = { onRetry(group.moduleUrl) },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        is GroupState.Loaded ->
                            if (groupState.sources.isEmpty()) {
                                MutedText(
                                    if (french) "${group.moduleName} · aucune source" else "${group.moduleName} · no sources",
                                    Modifier.padding(12.dp),
                                )
                            } else {
                                groupState.sources.forEach { item ->
                                    SourceRow(item, group.moduleName, french, onOther = { onOther(item) }) { onOpen(item) }
                                }
                            }
                    }
                }
            }
        }
    }
}

/**
 * Comme sur Android : `name` + `description`/`title` en entier (langues, taille, seeders… que les modules
 * mettent souvent sur plusieurs lignes), puis le type de source et le module.
 */
@Composable
private fun SourceRow(item: SourceItem, moduleName: String, french: Boolean, onOther: () -> Unit, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val playable = item.route.playable
    val kind = sourceKindLabel(item.route, french)
    val stream = item.stream
    val body = listOfNotNull(stream.name, stream.description ?: stream.title)
        .joinToString("\n")
        .ifBlank { kind }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .wmClickable(WmShape.Chip, enabled = playable, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                body,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onBackground,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$kind · $moduleName",
                style = MaterialTheme.typography.labelMedium,
                color = if (playable) colors.onSurfaceVariant else colors.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.route is Route.PlayDirect || item.route is Route.PlayViaRelay) {
            Box(
                Modifier
                    .size(40.dp)
                    .wmClickable(WmShape.Chip, hover = colors.outlineVariant, onClick = onOther)
                    .semantics { contentDescription = if (french) "Ouvrir avec une autre application" else "Open with another app" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(WmIcons.External, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        if (playable) Icon(WmIcons.Play, null, tint = colors.onBackground, modifier = Modifier.size(22.dp))
    }
}

/** Sur la fiche de l'épisode que regarde la salle : indique comment la rejoindre. */
@Composable
private fun PartyTitleHint(app: DesktopApp, ref: TitleRef, videoId: String?, french: Boolean) {
    val state by app.party.state.collectAsState()
    val s = state as? PartyState.InRoom ?: return
    val media = s.timeline?.media ?: return
    if (s.synced || media.type != ref.type || media.id != ref.id || media.videoId != videoId) return
    Text(
        if (french) "La salle regarde cet épisode : choisissez une source pour la rejoindre." else "The room is watching this episode: pick a source to join.",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
}
