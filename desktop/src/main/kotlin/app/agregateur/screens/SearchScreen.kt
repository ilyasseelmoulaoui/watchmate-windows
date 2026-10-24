package app.agregateur.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.agregateur.DesktopApp
import app.agregateur.TitleRef
import app.agregateur.core.addon.AddonResult
import app.agregateur.core.catalog.CatalogRef
import app.agregateur.core.catalog.searchableCatalogs
import app.agregateur.core.model.MetaPreview
import app.agregateur.ui.MutedText
import app.agregateur.ui.PosterRow
import app.agregateur.ui.ScreenTitle
import app.agregateur.ui.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private data class ResultRow(val ref: CatalogRef, val metas: List<MetaPreview>)

/** Résultats de la recherche de la barre de titre : une rangée par catalogue qui accepte une recherche. */
@Composable
fun SearchScreen(app: DesktopApp, query: String, french: Boolean, onOpenTitle: (TitleRef) -> Unit) {
    val modules by app.modules.modules.collectAsState()
    val refs = remember(modules) { searchableCatalogs(modules) }
    var rows by remember(query) { mutableStateOf<List<ResultRow>?>(null) }

    LaunchedEffect(query, refs) {
        rows = null
        rows = withContext(Dispatchers.IO) {
            coroutineScope {
                refs.map { ref ->
                    async {
                        (app.catalogs.search(ref, query) as? AddonResult.Success)?.value
                            ?.distinctBy { "${it.type}:${it.id}" }
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { ResultRow(ref, it) }
                    }
                }.awaitAll().filterNotNull()
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, top = 24.dp, bottom = 48.dp)) {
        item { ScreenTitle(if (french) "Résultats pour « $query »" else "Results for “$query”") }
        val current = rows
        when {
            refs.isEmpty() -> item {
                MutedText(
                    if (french) "Aucun module installé ne propose la recherche." else "No installed module supports search.",
                    Modifier.padding(top = 12.dp),
                )
            }
            current == null -> item { MutedText(if (french) "Recherche…" else "Searching…", Modifier.padding(top = 12.dp)) }
            current.isEmpty() -> item { MutedText(if (french) "Aucun résultat." else "No results.", Modifier.padding(top = 12.dp)) }
            else -> items(current, key = { it.ref.key }) { row ->
                SectionTitle(row.ref.name, row.ref.moduleName, Modifier.padding(top = 28.dp, bottom = 14.dp))
                PosterRow(
                    items = row.metas,
                    key = { "${it.type}:${it.id}" },
                    name = { it.name.ifBlank { it.id } },
                    poster = { it.poster },
                    onOpen = { onOpenTitle(TitleRef(it.type, it.id, row.ref.moduleUrl)) },
                )
            }
        }
    }
}
