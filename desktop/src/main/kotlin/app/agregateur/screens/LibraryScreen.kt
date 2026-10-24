package app.agregateur.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.agregateur.DesktopApp
import app.agregateur.TitleRef
import app.agregateur.core.library.continueWatching
import app.agregateur.core.library.savedEntries
import app.agregateur.ui.MutedText
import app.agregateur.ui.Poster
import app.agregateur.ui.ResumePoster
import app.agregateur.ui.ScreenTitle
import app.agregateur.ui.Segmented
import kotlinx.coroutines.launch

private enum class Shelf { Saved, Resume }

@Composable
fun LibraryScreen(app: DesktopApp, french: Boolean, onOpenTitle: (TitleRef) -> Unit) {
    val entries by app.library.entries.collectAsState()
    var shelf by remember { mutableStateOf(Shelf.Saved) }
    val scope = rememberCoroutineScope()
    val shown = remember(entries, shelf) {
        if (shelf == Shelf.Saved) entries.savedEntries() else entries.continueWatching()
    }

    fun removeResume(type: String, id: String) {
        scope.launch {
            if (!app.library.clearProgress(type, id)) {
                app.notify("Enregistrement impossible. Réessayez.", "Could not save. Try again.")
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(176.dp),
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScreenTitle(if (french) "Bibliothèque" else "Library")
                Segmented(
                    options = listOf(
                        Shelf.Saved to (if (french) "Ma liste" else "My list"),
                        Shelf.Resume to (if (french) "Reprendre" else "Continue"),
                    ),
                    selected = shelf,
                    onSelect = { shelf = it },
                )
            }
        }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                MutedText(
                    when (shelf) {
                        Shelf.Saved -> if (french) "Aucun titre dans votre liste pour l’instant." else "Nothing in your list yet."
                        Shelf.Resume -> if (french) "Rien en cours de lecture." else "Nothing in progress."
                    },
                )
            }
        }
        items(shown, key = { it.key }) { entry ->
            when (shelf) {
                Shelf.Saved -> Poster(
                    name = entry.name.ifBlank { entry.id },
                    poster = entry.poster,
                    seed = entry.key,
                    onClick = { onOpenTitle(TitleRef(entry.type, entry.id, entry.moduleUrl)) },
                    modifier = Modifier.fillMaxWidth().height(264.dp),
                )
                Shelf.Resume -> ResumePoster(
                    name = entry.name.ifBlank { entry.id },
                    poster = entry.poster,
                    seed = entry.key,
                    progressRatio = entry.progress?.ratio ?: 0f,
                    french = french,
                    onOpen = { onOpenTitle(TitleRef(entry.type, entry.id, entry.moduleUrl, entry.progress?.videoId)) },
                    onRemove = { removeResume(entry.type, entry.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
