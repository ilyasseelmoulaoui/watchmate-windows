package app.agregateur.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.agregateur.DesktopApp
import app.agregateur.TitleRef
import app.agregateur.core.addon.AddonResult
import app.agregateur.core.catalog.browsableCatalogs
import app.agregateur.core.library.continueWatching
import app.agregateur.core.model.MetaPreview
import app.agregateur.ui.ErrorLine
import app.agregateur.ui.MutedText
import app.agregateur.ui.PosterRow
import app.agregateur.ui.ResumePoster
import app.agregateur.ui.SectionTitle
import app.agregateur.ui.addonErrorMessage
import app.agregateur.ui.typeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class HomeRow(
    val key: String,
    val name: String,
    val moduleName: String,
    val moduleUrl: String,
    val metas: List<MetaPreview>,
)

@Composable
fun HomeScreen(app: DesktopApp, french: Boolean, onOpenTitle: (TitleRef) -> Unit) {
    val modules by app.modules.modules.collectAsState()
    val library by app.library.entries.collectAsState()
    val resume = remember(library) { library.continueWatching().take(8) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<HomeRow>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(modules) {
        val refs = browsableCatalogs(modules).take(8)
        if (refs.isEmpty()) {
            rows = emptyList()
            return@LaunchedEffect
        }
        loading = true
        error = null
        rows = withContext(Dispatchers.IO) {
            refs.mapNotNull { ref ->
                when (val result = app.catalogs.page(ref)) {
                    is AddonResult.Success -> HomeRow(
                        key = ref.key,
                        name = ref.name,
                        moduleName = "${ref.moduleName} · ${typeLabel(ref.type, french)}",
                        moduleUrl = ref.moduleUrl,
                        metas = result.value.distinctBy { "${it.type}:${it.id}" }.take(20),
                    ).takeIf { it.metas.isNotEmpty() }
                    is AddonResult.Failure -> {
                        error = addonErrorMessage(result.error, french)
                        null
                    }
                }
            }
        }
        loading = false
    }

    fun removeResume(type: String, id: String) {
        scope.launch {
            if (!app.library.clearProgress(type, id)) {
                app.notify("Enregistrement impossible. Réessayez.", "Could not save. Try again.")
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, top = 24.dp, bottom = 48.dp)) {
        if (resume.isNotEmpty()) {
            item(key = "resume") {
                SectionTitle(if (french) "Reprendre" else "Continue watching", modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                LazyRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(end = 40.dp),
                ) {
                    items(resume, key = { "${it.type}:${it.id}" }) { entry ->
                        ResumePoster(
                            name = entry.name.ifBlank { entry.id },
                            poster = entry.poster,
                            seed = "${entry.type}:${entry.id}",
                            progressRatio = entry.progress?.ratio ?: 0f,
                            french = french,
                            onOpen = { onOpenTitle(TitleRef(entry.type, entry.id, entry.moduleUrl, entry.progress?.videoId)) },
                            onRemove = { removeResume(entry.type, entry.id) },
                        )
                    }
                }
            }
        }
        when {
            modules.isEmpty() -> item {
                MutedText(
                    if (french) {
                        "Installe un module dans l’onglet Modules pour voir des catalogues ici."
                    } else {
                        "Install a module in the Modules tab to see catalogs here."
                    },
                    Modifier.padding(top = 24.dp),
                )
            }
            loading && rows.isEmpty() -> item {
                MutedText(if (french) "Chargement…" else "Loading…", Modifier.padding(top = 24.dp))
            }
            else -> {
                error?.let { item { ErrorLine(it, french, modifier = Modifier.padding(top = 8.dp)) } }
                items(rows, key = { it.key }) { row ->
                    SectionTitle(row.name, meta = row.moduleName, modifier = Modifier.padding(top = 28.dp, bottom = 14.dp))
                    PosterRow(
                        items = row.metas,
                        key = { "${it.type}:${it.id}" },
                        name = { it.name.ifBlank { it.id } },
                        poster = { it.poster },
                        onOpen = { onOpenTitle(TitleRef(it.type, it.id, row.moduleUrl)) },
                    )
                }
            }
        }
    }
}
