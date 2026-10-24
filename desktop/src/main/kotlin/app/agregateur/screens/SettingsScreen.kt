package app.agregateur.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.agregateur.DesktopApp
import app.agregateur.core.settings.APP_LANGUAGES
import app.agregateur.core.settings.SUBTITLE_LANGUAGES
import app.agregateur.core.settings.Settings
import app.agregateur.core.settings.ThemeMode
import app.agregateur.core.sync.AccountResult
import app.agregateur.core.sync.SupabaseError
import app.agregateur.player.VlcRuntime
import app.agregateur.sync.SyncStatus
import app.agregateur.ui.NeonButton
import app.agregateur.ui.OutlineButton
import app.agregateur.ui.QuietButton
import app.agregateur.ui.ScreenTitle
import app.agregateur.ui.SectionTitle
import app.agregateur.ui.WmTextField
import app.agregateur.ui.theme.WmIcons
import app.agregateur.ui.theme.WmShape
import app.agregateur.ui.theme.wm
import app.agregateur.ui.tr
import app.agregateur.ui.wmClickable
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: DesktopApp, french: Boolean) {
    val settings by app.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    fun change(transform: (Settings) -> Settings) {
        scope.launch {
            if (!app.settings.update(transform)) app.notify("Enregistrement impossible. Réessayez.", "Could not save. Try again.")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 48.dp),
    ) {
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle(tr("Réglages", "Settings"))

            SectionTitle(tr("Compte", "Account"), modifier = Modifier.padding(top = 24.dp))
            AccountSection(app)

            SectionTitle(tr("Apparence", "Appearance"), modifier = Modifier.padding(top = 28.dp))
            listOf(
                ThemeMode.SYSTEM to tr("Comme Windows", "Same as Windows"),
                ThemeMode.LIGHT to tr("Clair", "Light"),
                ThemeMode.DARK to tr("Sombre", "Dark"),
            ).forEach { (mode, label) ->
                ChoiceRow(label, settings.themeMode == mode) { change { it.copy(themeMode = mode) } }
            }

            SectionTitle(tr("Langue de l’application", "App language"), modifier = Modifier.padding(top = 28.dp))
            (listOf(null to tr("Comme Windows", "Same as Windows")) + APP_LANGUAGES).forEach { (code, name) ->
                ChoiceRow(name, settings.appLanguage == code) { change { it.copy(appLanguage = code) } }
            }

            SectionTitle(tr("Lecture", "Playback"), modifier = Modifier.padding(top = 28.dp))
            SwitchRow(
                tr("Toujours ouvrir dans une autre application", "Always open in another app"),
                tr(
                    "Les sources s’ouvrent dans le lecteur vidéo choisi dans Windows (VLC, MPC-HC…) au lieu du lecteur intégré.",
                    "Sources open in the video player chosen in Windows (VLC, MPC-HC…) instead of the built-in player.",
                ),
                settings.preferExternalPlayer,
            ) { checked -> change { it.copy(preferExternalPlayer = checked) } }
            Text(
                if (VlcRuntime.available) {
                    tr("Lecteur intégré : libVLC prêt.", "Built-in player: libVLC ready.")
                } else {
                    tr("Lecteur intégré : libVLC introuvable. Réinstallez WatchMate ou installez VLC 64 bits.", "Built-in player: libVLC not found. Reinstall WatchMate or install 64-bit VLC.")
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (VlcRuntime.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )

            SectionTitle(tr("Sous-titres", "Subtitles"), modifier = Modifier.padding(top = 28.dp))
            Text(
                tr("Langue choisie automatiquement au début de la lecture", "Language picked automatically when playback starts"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            (listOf(null to tr("Aucune (choix manuel)", "None (pick manually)")) + SUBTITLE_LANGUAGES).forEach { (code, name) ->
                ChoiceRow(name, settings.subtitleLanguage == code) { change { it.copy(subtitleLanguage = code) } }
            }

            SectionTitle(tr("Données", "Data"), modifier = Modifier.padding(top = 28.dp))
            OutlineButton(tr("Effacer l’historique de lecture", "Clear watch history"), { confirmClear = true })
            Text(
                tr("Dossier des données : ", "Data folder: ") + app.dataDir.absolutePath,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle(tr("À propos", "About"), modifier = Modifier.padding(top = 28.dp))
            Text(
                tr(
                    "WatchMate pour Windows vous permet de parcourir des catalogues, ouvrir des fiches et regarder des films ou des séries grâce aux modules que vous ajoutez. Avec un compte, votre bibliothèque suit l’app téléphone, et vous pouvez regarder ensemble avec des proches (bulle en bas à droite) : chacun lit avec ses propres modules, au même rythme. WatchMate ne fournit aucun contenu : tout vient des modules que vous choisissez d’installer.",
                    "WatchMate for Windows lets you browse catalogs, open titles, and watch films or series using modules you add. With an account, your library follows the phone app, and you can watch together with friends (bubble at the bottom right): everyone plays with their own modules, in step. WatchMate provides no content of its own: everything comes from the modules you choose to install.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(tr("Version 0.2.0", "Version 0.2.0"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Effacer l’historique de lecture", "Clear watch history")) },
            text = {
                Text(
                    tr(
                        "La rangée « Reprendre » et les positions de reprise seront effacées. Les titres de votre bibliothèque restent.",
                        "The “Continue watching” row and resume positions will be cleared. Titles in your library stay.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        if (app.library.clearAllProgress()) {
                            app.notify("Historique de lecture effacé.", "Watch history cleared.")
                        } else {
                            app.notify("Enregistrement impossible. Réessayez.", "Could not save. Try again.")
                        }
                    }
                }) { Text(tr("Effacer", "Clear")) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Annuler", "Cancel")) } },
        )
    }
}

@Composable
private fun AccountSection(app: DesktopApp) {
    val session by app.account.session.collectAsState()
    val status by app.sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var unconfirmed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val french = app.french

    fun run(action: suspend () -> AccountResult, onOk: Pair<String, String>?) {
        busy = true
        message = null
        scope.launch {
            val result = action()
            busy = false
            unconfirmed = result == AccountResult.ConfirmationSent ||
                (result as? AccountResult.Failed)?.error == SupabaseError.EMAIL_NOT_CONFIRMED
            message = when (result) {
                AccountResult.Ok -> onOk?.let { (fr, en) -> (if (french) fr else en) to false }
                AccountResult.ConfirmationSent -> (
                    if (french) "Compte créé. Ouvrez le lien reçu par e-mail pour le confirmer, puis connectez-vous."
                    else "Account created. Open the link in the email to confirm it, then sign in."
                    ) to false
                is AccountResult.Failed -> errorText(result.error, french) to true
            }
            if (result == AccountResult.Ok) password = ""
        }
    }

    val current = session
    if (current == null) {
        Text(
            tr(
                "Connectez-vous pour retrouver votre bibliothèque, votre progression, vos modules et vos réglages sur tous vos appareils.",
                "Sign in to find your library, progress, modules and settings on all your devices.",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WmTextField(email, { email = it; message = null }, tr("Adresse e-mail", "Email address"), Modifier.fillMaxWidth(), enabled = !busy)
        PasswordField(password, { password = it; message = null }, tr("Mot de passe", "Password"), enabled = !busy) {
            if (email.isNotBlank() && password.isNotEmpty()) run({ app.sync.signIn(email.trim(), password) }, null)
        }
        val ready = !busy && email.isNotBlank() && password.isNotEmpty()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeonButton(tr("Se connecter", "Sign in"), { run({ app.sync.signIn(email.trim(), password) }, null) }, enabled = ready)
            OutlineButton(tr("Créer un compte", "Create account"), { run({ app.sync.signUp(email.trim(), password) }, null) }, enabled = ready)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            QuietButton(tr("Mot de passe oublié ?", "Forgot password?"), {
                run(
                    { app.sync.requestPasswordReset(email.trim()) },
                    "Si un compte existe avec cette adresse, un e-mail vient d’être envoyé. Ouvrez son lien sur cet ordinateur." to
                        "If an account exists for this address, an email was just sent. Open its link on this computer.",
                )
            }, enabled = !busy && email.isNotBlank())
            if (unconfirmed) {
                QuietButton(tr("Renvoyer l’e-mail de confirmation", "Resend confirmation email"), {
                    run({ app.sync.resendConfirmation(email.trim()) }, "E-mail de confirmation renvoyé." to "Confirmation email sent again.")
                }, enabled = !busy && email.isNotBlank())
            }
        }
    } else {
        Text(current.user.email ?: current.user.id, style = MaterialTheme.typography.titleSmall)
        SyncLine(status, french)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton(tr("Synchroniser", "Sync now"), { app.sync.syncNow() }, enabled = status !is SyncStatus.Running)
            OutlineButton(tr("Se déconnecter", "Sign out"), { scope.launch { app.sync.signOut() } })
        }
        Text(
            tr("Supprimer mon compte", "Delete my account"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.wmClickable(WmShape.Chip, enabled = !busy) { confirmDelete = true }.padding(vertical = 8.dp, horizontal = 4.dp),
        )
    }
    message?.let { (text, error) ->
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Supprimer mon compte", "Delete my account")) },
            text = {
                Text(
                    tr(
                        "Votre compte et tout ce qui est enregistré en ligne seront supprimés définitivement. Ce qui est sur cet ordinateur reste.",
                        "Your account and everything stored online will be deleted for good. What is on this computer stays.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    run({ app.sync.deleteAccount() }, "Compte supprimé." to "Account deleted.")
                }) { Text(tr("Supprimer", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Annuler", "Cancel")) } },
        )
    }
}

/** L'état synchronisé garde sa couleur Synchro, toujours avec une icône et un texte. */
@Composable
private fun SyncLine(status: SyncStatus, french: Boolean) {
    val text = when (status) {
        SyncStatus.SignedOut -> return
        SyncStatus.Running -> if (french) "Synchronisation…" else "Syncing…"
        is SyncStatus.Idle -> status.lastSyncAt?.let {
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
            if (french) "Synchronisé à $time" else "Synced at $time"
        } ?: if (french) "Pas encore synchronisé" else "Not synced yet"
        is SyncStatus.Failed -> (if (french) "Synchronisation impossible : " else "Sync failed: ") + errorText(status.error, french)
    }
    val synced = status is SyncStatus.Idle && status.lastSyncAt != null
    val color = when (status) {
        is SyncStatus.Failed -> MaterialTheme.colorScheme.error
        is SyncStatus.Idle -> if (synced) MaterialTheme.wm.synchro else MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier
            .then(if (synced) Modifier.border(1.dp, color, WmShape.Field) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WmIcons.Pulse, null, tint = color, modifier = Modifier.size(24.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

fun errorText(error: SupabaseError, french: Boolean): String = when (error) {
    SupabaseError.INVALID_CREDENTIALS -> if (french) "Adresse ou mot de passe incorrect." else "Wrong email or password."
    SupabaseError.EMAIL_NOT_CONFIRMED -> if (french) "Adresse pas encore confirmée : ouvrez le lien reçu par e-mail." else "Address not confirmed yet: open the link in the email."
    SupabaseError.USER_EXISTS -> if (french) "Un compte existe déjà avec cette adresse." else "An account already exists for this address."
    SupabaseError.WEAK_PASSWORD -> if (french) "Mot de passe trop faible (au moins 6 caractères)." else "Password too weak (at least 6 characters)."
    SupabaseError.INVALID_EMAIL -> if (french) "Adresse e-mail invalide." else "Invalid email address."
    SupabaseError.RATE_LIMITED -> if (french) "Trop de tentatives. Réessayez dans quelques minutes." else "Too many attempts. Try again in a few minutes."
    SupabaseError.NETWORK -> if (french) "Pas de connexion au serveur." else "Cannot reach the server."
    SupabaseError.UNAUTHORIZED -> if (french) "Session expirée : reconnectez-vous." else "Session expired: sign in again."
    SupabaseError.SERVER -> if (french) "Erreur du serveur. Réessayez plus tard." else "Server error. Try again later."
}

/** Ouvert par un lien de réinitialisation valide : l'utilisateur est connecté et choisit son nouveau mot de passe. */
@Composable
fun PasswordResetDialog(app: DesktopApp) {
    val open by app.sync.choosePassword.collectAsState()
    if (!open) return
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) app.sync.dismissChoosePassword() },
        title = { Text(tr("Nouveau mot de passe", "New password")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Choisissez le nouveau mot de passe de votre compte (6 caractères au moins).", "Choose your account’s new password (at least 6 characters)."))
                PasswordField(password, { password = it; error = null }, tr("Mot de passe", "Password"), enabled = !busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && password.length >= MIN_PASSWORD,
                onClick = {
                    busy = true
                    scope.launch {
                        val result = app.sync.changePassword(password)
                        busy = false
                        error = (result as? AccountResult.Failed)?.let { errorText(it.error, app.french) }
                    }
                },
            ) { Text(tr("Enregistrer", "Save")) }
        },
        dismissButton = { TextButton(onClick = { app.sync.dismissChoosePassword() }, enabled = !busy) { Text(tr("Annuler", "Cancel")) } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .wmClickable(WmShape.Chip, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary, unselectedColor = MaterialTheme.wm.contour),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .wmClickable(WmShape.Chip, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.background),
        )
    }
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, placeholder: String, enabled: Boolean, onSubmit: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    var visible by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onBackground),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.fillMaxWidth().then(
            if (onSubmit != null) {
                Modifier.onEnter(onSubmit)
            } else {
                Modifier
            },
        ),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(colors.surfaceVariant, WmShape.Field)
                    .border(1.dp, MaterialTheme.wm.filet, WmShape.Field)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    inner()
                }
                QuietButton(
                    if (visible) tr("Masquer", "Hide") else tr("Afficher", "Show"),
                    { visible = !visible },
                )
            }
        },
    )
}

private fun Modifier.onEnter(action: () -> Unit): Modifier = this.then(
    Modifier.onPreviewKeyEvent {
        if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
            (it.key == androidx.compose.ui.input.key.Key.Enter || it.key == androidx.compose.ui.input.key.Key.NumPadEnter)
        ) {
            action()
            true
        } else {
            false
        }
    },
)

private const val MIN_PASSWORD = 6
