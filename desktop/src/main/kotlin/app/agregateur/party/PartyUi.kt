package app.agregateur.party

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agregateur.DesktopApp
import app.agregateur.core.party.MAX_CHAT
import app.agregateur.core.party.MAX_OFFSET_MS
import app.agregateur.core.party.PartyAction
import app.agregateur.core.party.PartyCodes
import app.agregateur.core.party.PartyMedia
import app.agregateur.core.party.PartyNotice
import app.agregateur.player.formatTime
import app.agregateur.ui.MutedText
import app.agregateur.ui.NeonButton
import app.agregateur.ui.OutlineButton
import app.agregateur.ui.QuietButton
import app.agregateur.ui.WmTextField
import app.agregateur.ui.theme.WmIcons
import app.agregateur.ui.theme.WmShape
import app.agregateur.ui.theme.generiqueFamily
import app.agregateur.ui.theme.wm
import app.agregateur.ui.tr
import kotlin.math.roundToInt

/**
 * Panneau « Regarder ensemble » (maquette « Windows — Lecteur et salle », 380 dp) : créer ou rejoindre une salle,
 * puis code, état de la synchro, membres, vocal, invitation, chat. Le même panneau sert hors lecture et à droite
 * du lecteur.
 */
@Composable
fun PartyPanel(app: DesktopApp, onJoinMedia: () -> Unit, joining: Boolean, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val state by app.party.state.collectAsState()
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (val s = state) {
                    is PartyState.InRoom -> tr("Salle de ${hostName(s)}", "${hostName(s)}’s room")
                    else -> tr("Regarder ensemble", "Watch together")
                },
                style = TextStyle(fontFamily = generiqueFamily(800), fontSize = 30.sp, lineHeight = 30.sp),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (onClose != null) QuietButton(tr("Fermer", "Close"), onClose, icon = WmIcons.Clear)
        }
        when (val s = state) {
            PartyState.Idle -> StartForm(app)
            is PartyState.Connecting -> MutedText(tr("Connexion à la salle…", "Connecting to the room…"))
            is PartyState.Failed -> {
                Text(failureText(s.failure), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                NeonButton("OK", app.party::dismissFailure, Modifier.fillMaxWidth())
            }
            is PartyState.InRoom -> RoomContent(app, s, onJoinMedia, joining)
        }
    }
}

@Composable
private fun StartForm(app: DesktopApp) {
    var code by remember { mutableStateOf("") }
    MutedText(
        tr(
            "Créez une salle et invitez vos amis. Chacun lit la vidéo avec ses propres modules ; WatchMate garde tout le monde au même moment (lecture, pause, avance, épisode).",
            "Create a room and invite your friends. Everyone plays the video with their own modules; WatchMate keeps everybody at the same moment (play, pause, seek, episode).",
        ),
    )
    NeonButton(tr("Créer une salle", "Create a room"), app.party::create, Modifier.fillMaxWidth(), height = 52.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Code d’une salle", "Room code"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            WmTextField(
                code,
                { code = it.take(20).uppercase() },
                "K7Q2-M9XA",
                Modifier.weight(1f),
                onSubmit = { if (PartyCodes.normalize(code) != null) app.party.join(code) },
            )
            OutlineButton(tr("Rejoindre", "Join"), { app.party.join(code) }, enabled = PartyCodes.normalize(code) != null)
        }
    }
}

@Composable
private fun RoomContent(app: DesktopApp, s: PartyState.InRoom, onJoinMedia: () -> Unit, joining: Boolean) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Code de la salle", "Room code"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            QuietButton(tr("Copier le code", "Copy code"), {
                clipboard.setText(AnnotatedString(PartyCodes.display(s.code)))
                app.notify("Code copié.", "Code copied.")
            }, icon = WmIcons.Copy)
        }
        CodeBoxes(s.code)
        QuietButton(tr("Copier le lien d’invitation", "Copy invite link"), {
            clipboard.setText(AnnotatedString(PartyCodes.link(s.code)))
            app.notify("Lien copié : il ouvre WatchMate sur un autre ordinateur ou téléphone.", "Link copied: it opens WatchMate on another computer or phone.")
        }, icon = WmIcons.External)
    }

    SyncStatus(s)

    val timeline = s.timeline
    if (timeline != null && !s.synced) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                tr("${s.nameOf(timeline.by)} regarde « ${mediaLabel(timeline.media)} »", "${s.nameOf(timeline.by)} is watching “${mediaLabel(timeline.media)}”"),
                style = MaterialTheme.typography.bodyMedium,
            )
            NeonButton(
                if (joining) tr("Recherche d’une source sur cet ordinateur…", "Finding a source on this computer…") else tr("Regarder aussi", "Watch too"),
                onJoinMedia,
                Modifier.fillMaxWidth(),
                enabled = !joining,
            )
        }
    }

    Members(s)
    VoiceSection(app, s)
    InviteByEmail(app)
    RoomControls(app, s)
    ChatSection(app, s)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MutedText(tr("Aucune vidéo ne passe entre vous : chacun lit sa propre source.", "No video passes between you: everyone plays their own source."))
        OutlineButton(tr("Quitter la salle", "Leave the room"), app.party::leave, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CodeBoxes(code: String) {
    val display = PartyCodes.display(code).replace("-", "")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        display.chunked(4).forEach { group ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                group.forEach { char ->
                    Box(
                        Modifier.weight(1f).height(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(char.toString(), style = TextStyle(fontFamily = generiqueFamily(800), fontSize = 34.sp, lineHeight = 34.sp))
                    }
                }
            }
        }
    }
}

/** État de la synchro : couleur Synchro toujours accompagnée d'une icône et d'un texte. */
@Composable
private fun SyncStatus(s: PartyState.InRoom) {
    val synchro = MaterialTheme.wm.synchro
    val (title, detail, color) = when {
        !s.connected -> Triple(tr("Connexion perdue", "Connection lost"), tr("Nouvelle tentative…", "Retrying…"), MaterialTheme.colorScheme.error)
        s.waitingFor.isNotEmpty() -> Triple(
            tr("En attente", "Waiting"),
            tr("En attente de ${s.waitingFor.joinToString(", ") { s.nameOf(it) }}…", "Waiting for ${s.waitingFor.joinToString(", ") { s.nameOf(it) }}…"),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        s.synced && s.timeline != null -> Triple(
            tr("Synchronisé", "In sync"),
            if (s.timeline.playing) tr("Tout le monde regarde au même moment", "Everyone is at the same moment") else tr("En pause pour tout le monde", "Paused for everyone"),
            synchro,
        )
        s.timeline == null -> Triple(tr("Prêts", "Ready"), tr("Lancez un titre : la salle le suivra.", "Start a title: the room will follow."), synchro)
        else -> Triple(tr("Pas encore avec la salle", "Not with the room yet"), tr("Choisissez « Regarder aussi ».", "Choose “Watch too”."), MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(
        Modifier.fillMaxWidth().border(1.dp, color, WmShape.Field).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WmIcons.Pulse, null, tint = color, modifier = Modifier.size(24.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Members(s: PartyState.InRoom) {
    Column {
        Text(tr("Dans la salle", "In the room"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text(membersCount(s.members.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.members.forEach { m ->
            val self = m.id == s.selfId
            val host = m.userId == s.hostId
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(36.dp).background(if (self) MaterialTheme.colorScheme.primary else Color(0xFFD9C7A7), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(m.name.take(2).uppercase(), style = MaterialTheme.typography.labelLarge, color = Color(0xFF1B1215))
                }
                val tags = listOfNotNull(tr("vous", "you").takeIf { self }, tr("hôte", "host").takeIf { host }).joinToString(", ")
                Text(
                    buildAnnotatedString {
                        append(m.name)
                        if (tags.isNotEmpty()) withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" ($tags)") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (m.voice) Icon(if (m.muted) WmIcons.MicOff else WmIcons.Mic, tr("Au vocal", "On voice"), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                if (m.watching) Icon(WmIcons.Check, tr("Synchronisé", "In sync"), tint = MaterialTheme.wm.synchro, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun VoiceSection(app: DesktopApp, s: PartyState.InRoom) {
    val peers = s.members.filter { it.voice }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Vocal", "Voice"), style = MaterialTheme.typography.titleMedium)
        if (peers.isNotEmpty()) {
            val you = tr("vous", "you")
            val labels = peers.map { m ->
                val self = m.id == s.selfId
                val status = when {
                    self -> null
                    m.muted -> tr("micro coupé", "mic off")
                    !s.inVoice -> null
                    m.id in s.voiceConnected -> tr("connecté", "connected")
                    else -> tr("connexion…", "connecting…")
                }
                val name = if (self) you else m.name
                if (status != null) "$name ($status)" else name
            }
            MutedText(tr("Au vocal : ", "On voice: ") + labels.joinToString(", "))
        }
        if (!s.inVoice) {
            NeonButton(tr("Rejoindre le vocal", "Join voice"), {
                if (!app.party.joinVoice()) {
                    app.notify("Micro ou vocal indisponible sur cet ordinateur.", "Microphone or voice is not available on this computer.")
                }
            }, Modifier.fillMaxWidth(), icon = WmIcons.Mic)
            Text(
                tr(
                    "L’audio passe directement d’un appareil à l’autre. Avec un casque, le son du film ne repart pas dans votre micro.",
                    "Audio goes straight between devices. Headphones keep the film’s sound out of your mic.",
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton(if (s.muted) tr("Activer le micro", "Unmute mic") else tr("Couper le micro", "Mute mic"), { app.party.setMuted(!s.muted) }, Modifier.weight(1f))
                OutlineButton(tr("Quitter le vocal", "Leave voice"), app.party::leaveVoice, Modifier.weight(1f))
            }
            if (peers.none { it.id != s.selfId }) {
                Text(tr("Personne d’autre au vocal pour l’instant.", "Nobody else is on voice yet."), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InviteByEmail(app: DesktopApp) {
    var email by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        app.party.inviteSend.collect { outcome ->
            when (outcome) {
                PartyInviteOutcome.OK -> {
                    email = ""
                    app.notify("Invitation envoyée.", "Invite sent.")
                }
                PartyInviteOutcome.NOT_FOUND -> app.notify("Aucun compte WatchMate avec cette adresse.", "No WatchMate account with this address.")
                PartyInviteOutcome.SELF -> app.notify("Vous ne pouvez pas vous inviter vous-même.", "You cannot invite yourself.")
                PartyInviteOutcome.INVALID -> app.notify("Adresse e-mail invalide.", "Invalid email address.")
                PartyInviteOutcome.NOT_SET_UP -> app.notify("Regarder ensemble n’est pas encore activé sur le serveur.", "Watch together is not enabled on the server yet.")
                PartyInviteOutcome.NOT_SIGNED_IN -> app.notify("Connectez-vous d’abord (Réglages → Compte).", "Sign in first (Settings → Account).")
                PartyInviteOutcome.NOT_MEMBER, PartyInviteOutcome.NETWORK -> app.notify("Connexion impossible. Vérifiez votre connexion internet.", "Cannot connect. Check your internet connection.")
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Inviter par e-mail", "Invite by email"), style = MaterialTheme.typography.titleMedium)
        WmTextField(email, { email = it.take(120) }, "prenom@exemple.fr", Modifier.fillMaxWidth(), onSubmit = { if ('@' in email) app.party.inviteByEmail(email.trim()) })
        NeonButton(tr("Envoyer l’invitation", "Send invite"), { app.party.inviteByEmail(email.trim()) }, Modifier.fillMaxWidth(), enabled = '@' in email)
        Text(
            tr(
                "Si votre ami a déjà un compte WatchMate avec cette adresse, il reçoit une invitation dans l’app (pas d’e-mail).",
                "If your friend already has a WatchMate account with this address, they get an in-app invite (no email is sent).",
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RoomControls(app: DesktopApp, s: PartyState.InRoom) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.isHost) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Hôte seul", "Host only"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (s.hostOnly) {
                            tr("Seul l’hôte peut mettre en pause, avancer ou changer le titre", "Only the host can pause, seek or change the title")
                        } else {
                            tr("Tout le monde dans la salle peut piloter la lecture", "Anyone in the room can control playback")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = s.hostOnly,
                    onCheckedChange = app.party::setHostOnly,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.background),
                )
            }
        } else if (s.hostOnly) {
            MutedText(tr("Seul l’hôte peut piloter la lecture", "Only the host can control playback"))
        }
        val seconds = s.offsetMs / 1000f
        Text(tr("Décalage", "Sync offset") + " · " + formatOffsetSeconds(seconds) + " s", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = seconds,
            onValueChange = { app.party.setOffsetMs((it * 1000f).roundToInt().toLong()) },
            valueRange = -MAX_OFFSET_MS / 1000f..MAX_OFFSET_MS / 1000f,
            steps = 59,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun ChatSection(app: DesktopApp, s: PartyState.InRoom) {
    val lines by app.party.chat.collectAsState()
    var draft by remember { mutableStateOf("") }
    fun send() {
        if (draft.isNotBlank() && app.party.sendChat(draft.trim())) draft = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Chat", "Chat"), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            WmTextField(draft, { draft = it.take(MAX_CHAT) }, tr("Message", "Message"), Modifier.weight(1f), onSubmit = ::send)
            QuietButton(tr("Envoyer", "Send"), ::send, icon = WmIcons.Send, enabled = draft.isNotBlank())
        }
        if (lines.isEmpty()) MutedText(tr("Aucun message", "No messages yet"))
        val you = tr("vous", "you")
        lines.asReversed().take(MAX_SHOWN).forEach { line ->
            val who = if (line.from == s.selfId) you else s.nameOf(line.from)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) { append(who) }
                    append("  ")
                    append(line.text)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Confirmation d'un lien d'invitation : on n'entre jamais dans une salle sans l'avoir accepté. */
@Composable
fun PartyInviteDialogs(app: DesktopApp) {
    val code by app.party.invite.collectAsState()
    code?.let { current ->
        AlertDialog(
            onDismissRequest = app.party::dismissInvite,
            title = { Text(tr("Rejoindre la salle ${PartyCodes.display(current)} ?", "Join room ${PartyCodes.display(current)}?")) },
            text = {
                Text(
                    tr(
                        "Les membres de la salle verront votre nom (le début de votre adresse e-mail) et pourront mettre en pause, avancer ou changer ce que vous regardez.",
                        "Room members will see your name (the start of your email address) and can pause, seek or change what you watch together.",
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { app.party.dismissInvite(); app.party.join(current) }) { Text(tr("Rejoindre", "Join")) } },
            dismissButton = { TextButton(onClick = app.party::dismissInvite) { Text(tr("Annuler", "Cancel")) } },
        )
    }
    val pending by app.party.pendingInvites.collectAsState()
    pending.firstOrNull()?.let { invite ->
        AlertDialog(
            onDismissRequest = { app.party.dismissPendingInvite(invite) },
            title = { Text(tr("Invitation à regarder ensemble", "Watch-together invite")) },
            text = { Text(tr("${invite.from} vous invite dans la salle ${PartyCodes.display(invite.code)}.", "${invite.from} invites you to room ${PartyCodes.display(invite.code)}.")) },
            confirmButton = { TextButton(onClick = { app.party.acceptInvite(invite) }) { Text(tr("Rejoindre", "Join")) } },
            dismissButton = { TextButton(onClick = { app.party.dismissPendingInvite(invite) }) { Text(tr("Refuser", "Decline")) } },
        )
    }
}

@Composable
fun membersCount(n: Int): String = if (n == 1) tr("1 personne", "1 person") else tr("$n personnes", "$n people")

@Composable
fun noticeText(n: PartyNotice, s: PartyState.InRoom): String {
    val who = s.nameOf(n.from)
    return when (n.action) {
        PartyAction.PLAY -> tr("$who : lecture", "$who pressed play")
        PartyAction.PAUSE -> tr("$who : pause", "$who paused")
        PartyAction.SEEK -> tr("$who : saut à ${formatTime(n.positionMs)}", "$who jumped to ${formatTime(n.positionMs)}")
        PartyAction.LOAD -> tr("$who a lancé « ${mediaLabel(n.media)} »", "$who started “${mediaLabel(n.media)}”")
    }
}

@Composable
private fun failureText(failure: PartyFailure): String = when (failure) {
    PartyFailure.NOT_SIGNED_IN -> tr("Connectez-vous d’abord (Réglages → Compte) : il faut un compte pour regarder ensemble.", "Sign in first (Settings → Account): watching together needs an account.")
    PartyFailure.NOT_FOUND -> tr("Aucune salle avec ce code (une salle expire après 24 h).", "No room with this code (rooms expire after 24 h).")
    PartyFailure.FULL -> tr("Cette salle est pleine (20 personnes).", "This room is full (20 people).")
    PartyFailure.NOT_SET_UP -> tr("Regarder ensemble n’est pas encore activé sur le serveur (supabase/watch_party.sql).", "Watch together is not enabled on the server yet (supabase/watch_party.sql).")
    PartyFailure.NETWORK -> tr("Connexion impossible. Vérifiez votre connexion internet.", "Cannot connect. Check your internet connection.")
    PartyFailure.REJECTED -> tr("Accès à la salle refusé.", "Access to the room was refused.")
}

private fun hostName(s: PartyState.InRoom): String =
    s.members.firstOrNull { it.userId == s.hostId }?.name ?: s.members.firstOrNull()?.name ?: PartyCodes.display(s.code)

fun mediaLabel(media: PartyMedia): String {
    val parts = media.videoId.split(':')
    val episode = if (media.videoId != media.id && parts.size >= 3) " S${parts[parts.size - 2]}E${parts.last()}" else ""
    return media.title + episode
}

private fun formatOffsetSeconds(seconds: Float): String {
    val value = (seconds * 10).roundToInt() / 10f
    return if (value > 0) "+$value" else value.toString()
}

private const val MAX_SHOWN = 60
