package app.agregateur.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.agregateur.DesktopApp
import app.agregateur.core.modules.InstallResult
import app.agregateur.core.modules.Origin
import app.agregateur.core.modules.SUGGESTED_MODULES
import kotlinx.coroutines.launch

@Composable
fun ModulesScreen(app: DesktopApp, french: Boolean) {
    val installed by app.modules.modules.collectAsState()
    var address by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun install(url: String, origin: Origin = Origin.ADDRESS) {
        busy = true
        message = null
        scope.launch {
            message = when (val result = app.modules.install(url, origin)) {
                is InstallResult.Installed -> if (french) "Installé." else "Installed."
                is InstallResult.AlreadyInstalled -> if (french) "Déjà installé." else "Already installed."
                is InstallResult.Failed -> result.error.toString()
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (french) "Modules" else "Modules", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (french) {
                "WatchMate n’apporte aucun contenu : les catalogues et sources viennent des modules que tu installes."
            } else {
                "WatchMate provides no content: catalogs and sources come from modules you install."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(if (french) "Adresse du module" else "Module address") },
                enabled = !busy,
            )
            Button(onClick = { install(address) }, enabled = !busy && address.isNotBlank()) {
                Text(if (french) "Installer" else "Install")
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Text(if (french) "Suggestions" else "Suggested", style = MaterialTheme.typography.titleMedium)
        SUGGESTED_MODULES.forEach { suggested ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(suggested.name)
                    Text(suggested.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(
                    onClick = { install(suggested.manifestUrl, Origin.LIST) },
                    enabled = !busy,
                ) { Text(if (french) "Ajouter" else "Add") }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(if (french) "Installés" else "Installed", style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(installed, key = { it.manifestUrl }) { module ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(module.manifest.name.ifBlank { module.manifestUrl })
                    OutlinedButton(
                        onClick = {
                            scope.launch { app.modules.remove(module.manifestUrl) }
                        },
                        enabled = !busy,
                    ) { Text(if (french) "Retirer" else "Remove") }
                }
            }
        }
    }
}
