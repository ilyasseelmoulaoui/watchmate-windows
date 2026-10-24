package app.agregateur.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import app.agregateur.core.addon.AddonResult
import app.agregateur.core.catalog.browsableCatalogs
import app.agregateur.core.model.MetaPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(app: DesktopApp, french: Boolean) {
    val modules by app.modules.modules.collectAsState()
    var rows by remember { mutableStateOf<List<Pair<String, List<MetaPreview>>>>(emptyList()) }
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
                    is AddonResult.Success ->
                        ref.name to result.value.distinctBy { "${it.type}:${it.id}" }.take(20)
                    is AddonResult.Failure -> {
                        error = result.error.toString()
                        null
                    }
                }
            }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Text(if (french) "Accueil" else "Home", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        when {
            modules.isEmpty() -> Text(
                if (french) {
                    "Installe un module dans l’onglet Modules pour voir des catalogues ici."
                } else {
                    "Install a module in the Modules tab to see catalogs here."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            loading && rows.isEmpty() -> Text(
                if (french) "Chargement…" else "Loading…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn {
                    items(rows, key = { it.first }) { (name, metas) ->
                        Spacer(Modifier.height(12.dp))
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        metas.forEach { meta ->
                            Text(meta.name.ifBlank { meta.id }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
