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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.agregateur.DesktopApp

@Composable
fun LibraryScreen(app: DesktopApp, french: Boolean) {
    val entries by app.library.entries.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Text(if (french) "Bibliothèque" else "Library", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text(
                if (french) "Aucun titre enregistré pour l’instant." else "No saved titles yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn {
                items(entries, key = { "${it.type}:${it.id}" }) { entry ->
                    Text(entry.name.ifBlank { entry.id }, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
