package app.agregateur.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.agregateur.DesktopApp
import app.agregateur.TitleRef
import app.agregateur.core.addon.AddonError
import app.agregateur.core.catalog.CatalogPager
import app.agregateur.core.catalog.CatalogRef
import app.agregateur.core.catalog.CatalogService
import app.agregateur.core.catalog.browsableCatalogs
import app.agregateur.core.model.MetaPreview
import app.agregateur.core.modules.ModuleRepository
import app.agregateur.ui.ErrorLine
import app.agregateur.ui.MutedText
import app.agregateur.ui.Poster
import app.agregateur.ui.ScreenTitle
import app.agregateur.ui.Segmented
import app.agregateur.ui.WmChip
import app.agregateur.ui.addonErrorMessage
import app.agregateur.ui.theme.WmType
import app.agregateur.ui.typeLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class ExploreUiState(
    val types: List<String> = emptyList(),
    val type: String? = null,
    val catalogs: List<CatalogRef> = emptyList(),
    val catalog: CatalogRef? = null,
    val genre: String? = null,
    val items: List<MetaPreview> = emptyList(),
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val error: AddonError? = null,
)

private class ExploreController(
    private val modules: ModuleRepository,
    private val service: CatalogService,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ExploreUiState())
    val state: StateFlow<ExploreUiState> = _state.asStateFlow()

    private var all: List<CatalogRef> = emptyList()
    private var pager: CatalogPager? = null
    private var job: Job? = null
    private var modulesJob: Job? = null

    fun start() {
        modulesJob?.cancel()
        modulesJob = scope.launch {
            modules.modules
                .map(::browsableCatalogs)
                .distinctUntilChanged()
                .collectLatest { refs ->
                    all = refs
                    val current = _state.value
                    select(current.type, current.catalog, current.genre)
                }
        }
    }

    fun stop() {
        modulesJob?.cancel()
        job?.cancel()
    }

    fun selectType(type: String) = select(type, null, null)

    fun selectCatalog(catalog: CatalogRef) = select(catalog.type, catalog, null)

    fun selectGenre(genre: String?) = select(_state.value.type, _state.value.catalog, genre)

    private fun select(type: String?, catalog: CatalogRef?, genre: String?) {
        job?.cancel()
        val types = all.map { it.type }.distinct()
        val chosenType = type?.takeIf { it in types } ?: types.firstOrNull()
        val catalogs = all.filter { it.type == chosenType }
        val chosenCatalog = catalog?.takeIf { it in catalogs } ?: catalogs.firstOrNull()
        val chosenGenre = genre?.takeIf { chosenCatalog != null && it in chosenCatalog.genres }
        pager = chosenCatalog?.let { CatalogPager(service, it, chosenGenre) }
        _state.value = ExploreUiState(types, chosenType, catalogs, chosenCatalog, chosenGenre)
        loadMore()
    }

    fun loadMore() {
        val current = pager ?: return
        if (_state.value.loading || current.endReached) return
        _state.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            val error = current.loadMore()
            _state.update {
                it.copy(
                    loading = false,
                    items = current.items,
                    endReached = current.endReached,
                    error = error,
                )
            }
        }
    }
}

@Composable
fun ExploreScreen(app: DesktopApp, french: Boolean, onOpenTitle: (TitleRef) -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember(app) { ExploreController(app.modules, app.catalogs, scope) }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.stop() }
    }
    val state by controller.state.collectAsState()

    if (state.types.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 24.dp)) {
            ScreenTitle(if (french) "Explorer" else "Explore")
            MutedText(
                if (french) {
                    "Installez un module dans l’onglet Modules pour parcourir des catalogues."
                } else {
                    "Install a module in the Modules tab to browse catalogs."
                },
                Modifier.padding(top = 12.dp),
            )
        }
        return
    }

    PosterGrid(state, french, controller, onOpenTitle)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(state: ExploreUiState, french: Boolean, controller: ExploreController) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenTitle(if (french) "Explorer" else "Explore")
            if (state.types.size > 1) {
                Segmented(
                    options = state.types.map { it to typeLabel(it, french) },
                    selected = state.type ?: state.types.first(),
                    onSelect = controller::selectType,
                )
            }
        }
        if (state.catalogs.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (french) "Catalogue" else "Catalog", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.catalogs.forEach { catalog ->
                        WmChip(
                            "${catalog.name} · ${catalog.moduleName}",
                            selected = catalog == state.catalog,
                            onClick = { controller.selectCatalog(catalog) },
                        )
                    }
                }
            }
        }
        state.catalog?.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (french) "Genre" else "Genre", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WmChip(if (french) "Tous" else "All", selected = state.genre == null, onClick = { controller.selectGenre(null) })
                    genres.forEach { genre ->
                        WmChip(genre, selected = genre == state.genre, onClick = { controller.selectGenre(genre) })
                    }
                }
            }
        }
    }
}

/** Colonnes adaptatives (min. 180 dp), hauteurs 300 / 220 en alternance. */
@Composable
private fun PosterGrid(
    state: ExploreUiState,
    french: Boolean,
    controller: ExploreController,
    onOpenTitle: (TitleRef) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    LaunchedEffect(gridState, state.items.size, state.loading, state.endReached, state.error) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (state.error == null && last >= state.items.size - PREFETCH_DISTANCE) controller.loadMore()
            }
    }

    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Adaptive(180.dp),
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalItemSpacing = 16.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "filters", span = StaggeredGridItemSpan.FullLine) { Filters(state, french, controller) }
        itemsIndexed(state.items, key = { _, it -> "${it.type}:${it.id}" }) { index, item ->
            val tall = (index % 4 == 0) || (index % 4 == 3)
            Poster(
                name = item.name.ifBlank { item.id },
                poster = item.poster,
                seed = "${item.type}:${item.id}",
                onClick = { state.catalog?.let { onOpenTitle(TitleRef(item.type, item.id, it.moduleUrl)) } },
                modifier = Modifier.fillMaxWidth().height(if (tall) 300.dp else 220.dp),
                style = WmType.Affiche,
            )
        }
        item(key = "footer", span = StaggeredGridItemSpan.FullLine) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                val error = state.error
                when {
                    error != null -> ErrorLine(addonErrorMessage(error, french), french, onRetry = controller::loadMore)
                    state.loading -> MutedText(if (french) "Chargement…" else "Loading…")
                    state.items.isEmpty() && state.endReached -> MutedText(if (french) "Catalogue vide." else "Empty catalog.")
                }
            }
        }
    }
}

private const val PREFETCH_DISTANCE = 6
