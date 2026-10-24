package app.agregateur.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.agregateur.DesktopApp
import app.agregateur.core.settings.APP_LANGUAGES
import app.agregateur.core.settings.ThemeMode
import app.agregateur.core.sync.AccountResult
import app.agregateur.sync.SyncStatus
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: DesktopApp, french: Boolean) {
    val settings by app.settings.settings.collectAsState()
    val session by app.account.session.collectAsState()
    val syncStatus by app.sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(if (french) "Réglages" else "Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Text(if (french) "Compte" else "Account", style = MaterialTheme.typography.titleMedium)
        if (session == null) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("E-mail") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (french) "Mot de passe" else "Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                OutlinedButton(onClick = {
                    scope.launch {
                        message = when (val r = app.sync.signIn(email, password)) {
                            AccountResult.Ok -> if (french) "Connecté." else "Signed in."
                            AccountResult.ConfirmationSent -> if (french) "Confirme ton e-mail." else "Confirm your e-mail."
                            is AccountResult.Failed -> r.error.name
                        }
                    }
                }) { Text(if (french) "Se connecter" else "Sign in") }
                OutlinedButton(onClick = {
                    scope.launch {
                        message = when (val r = app.sync.signUp(email, password)) {
                            AccountResult.Ok -> if (french) "Compte créé." else "Account created."
                            AccountResult.ConfirmationSent -> if (french) "Confirme ton e-mail." else "Confirm your e-mail."
                            is AccountResult.Failed -> r.error.name
                        }
                    }
                }) { Text(if (french) "Créer un compte" else "Sign up") }
            }
        } else {
            Text(session!!.user.email ?: session!!.user.id)
            Text(
                when (val s = syncStatus) {
                    SyncStatus.SignedOut -> ""
                    SyncStatus.Running -> if (french) "Synchronisation…" else "Syncing…"
                    is SyncStatus.Idle -> if (french) "Synchronisé" else "Synced"
                    is SyncStatus.Failed -> s.error.name
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                OutlinedButton(onClick = { app.sync.syncNow() }) {
                    Text(if (french) "Synchroniser" else "Sync now")
                }
                OutlinedButton(onClick = { scope.launch { app.sync.signOut() } }) {
                    Text(if (french) "Se déconnecter" else "Sign out")
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Spacer(Modifier.height(16.dp))
        Text(if (french) "Apparence" else "Appearance", style = MaterialTheme.typography.titleMedium)
        listOf(
            ThemeMode.SYSTEM to (if (french) "Comme le système" else "System default"),
            ThemeMode.LIGHT to (if (french) "Clair" else "Light"),
            ThemeMode.DARK to (if (french) "Sombre" else "Dark"),
        ).forEach { (mode, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { scope.launch { app.settings.update { it.copy(themeMode = mode) } } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.themeMode == mode, onClick = {
                    scope.launch { app.settings.update { it.copy(themeMode = mode) } }
                })
                Text(label)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(if (french) "Langue de l’application" else "App language", style = MaterialTheme.typography.titleMedium)
        (listOf(null to (if (french) "Comme le système" else "System default")) + APP_LANGUAGES).forEach { (code, name) ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    scope.launch { app.settings.update { it.copy(appLanguage = code) } }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.appLanguage == code, onClick = {
                    scope.launch { app.settings.update { it.copy(appLanguage = code) } }
                })
                Text(name)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(if (french) "À propos" else "About", style = MaterialTheme.typography.titleMedium)
        Text(
            if (french) {
                "WatchMate pour Windows te permet de parcourir des catalogues, ouvrir des fiches et regarder des films ou des séries grâce aux modules que tu ajoutes. Avec un compte, synchronise ta bibliothèque avec l’app téléphone. WatchMate ne fournit aucun contenu : tout vient des modules que tu choisis d’installer."
            } else {
                "WatchMate for Windows lets you browse catalogs, open titles, and watch films or series using modules you add. With an account, sync your library with the phone app. WatchMate provides no content of its own: everything comes from the modules you choose to install."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text("0.1.0", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}
