package app.agregateur.party

import app.agregateur.core.party.PartyChatLine
import app.agregateur.core.party.PartyCodec
import app.agregateur.core.party.PartyCodes
import app.agregateur.core.party.PartyEnvelope
import app.agregateur.core.party.PartyMedia
import app.agregateur.core.party.PartyMember
import app.agregateur.core.party.PartyMembers
import app.agregateur.core.party.PartyNotice
import app.agregateur.core.party.PartyPlayer
import app.agregateur.core.party.PartyPresence
import app.agregateur.core.party.PartySync
import app.agregateur.core.party.PartyTimeline
import app.agregateur.core.party.VoiceCodec
import app.agregateur.core.party.VoiceEnvelope
import app.agregateur.core.sync.AccountService
import app.agregateur.core.sync.ChannelEvent
import app.agregateur.core.sync.PartyInvite
import app.agregateur.core.sync.RealtimeChannel
import app.agregateur.core.sync.SupabaseClient
import app.agregateur.core.sync.SupabaseConfig
import app.agregateur.core.sync.SupabaseError
import app.agregateur.core.sync.SupabaseException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

enum class PartyFailure { NOT_SIGNED_IN, NOT_FOUND, FULL, NOT_SET_UP, NETWORK, REJECTED }

/** Résultat d'une invitation par e-mail (in-app). */
enum class PartyInviteOutcome { OK, NOT_FOUND, NOT_MEMBER, SELF, INVALID, NETWORK, NOT_SET_UP, NOT_SIGNED_IN }

sealed interface PartyState {
    data object Idle : PartyState
    data class Connecting(val code: String?) : PartyState
    data class Failed(val failure: PartyFailure) : PartyState
    data class InRoom(
        val code: String,
        val selfId: String,
        val hostId: String?,
        val connected: Boolean,
        val members: List<PartyMember>,
        val timeline: PartyTimeline?,
        val synced: Boolean,
        val waitingFor: List<String>,
        val hostOnly: Boolean,
        val offsetMs: Long,
        val canControl: Boolean,
        val isHost: Boolean,
        val inVoice: Boolean = false,
        val muted: Boolean = false,
        val voiceConnected: Set<String> = emptySet(),
    ) : PartyState {
        fun nameOf(id: String): String = members.firstOrNull { it.id == id }?.name ?: "?"
    }
}

/**
 * Salle « regarder ensemble » : création ou entrée par code (fonctions SQL de `supabase/watch_party.sql`), canal
 * Realtime privé, et [PartySync] qui aligne le lecteur local sur les autres. Tout s'exécute sur le fil principal.
 */
class PartyManager(
    private val account: AccountService,
    private val client: SupabaseClient,
    private val config: SupabaseConfig,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val voice: VoiceChat,
) {
    private val _state = MutableStateFlow<PartyState>(PartyState.Idle)
    val state: StateFlow<PartyState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<PartyNotice>(extraBufferCapacity = 16)
    val notices: SharedFlow<PartyNotice> = _notices.asSharedFlow()

    private val _chat = MutableStateFlow<List<PartyChatLine>>(emptyList())
    val chat: StateFlow<List<PartyChatLine>> = _chat.asStateFlow()

    private var room: Room? = null

    init {
        scope.launch { voice.connected.collect { publish() } }
    }

    private class Room(val code: String, val name: String, val sync: PartySync, val channel: RealtimeChannel, val jobs: List<Job>) {
        var connected = false
        var members: List<PartyMember> = emptyList()
        var tracked: PartyPresence? = null
    }

    /** Code reçu par un lien `watchmate://party/<code>` : on demande confirmation avant d'entrer dans la salle. */
    private val _invite = MutableStateFlow<String?>(null)
    val invite: StateFlow<String?> = _invite.asStateFlow()

    /** Invitations e-mail reçues (compte WatchMate existant). */
    private val _pendingInvites = MutableStateFlow<List<PartyInvite>>(emptyList())
    val pendingInvites: StateFlow<List<PartyInvite>> = _pendingInvites.asStateFlow()

    private val _inviteSend = MutableSharedFlow<PartyInviteOutcome>(extraBufferCapacity = 4)
    val inviteSend: SharedFlow<PartyInviteOutcome> = _inviteSend.asSharedFlow()

    private var invitePoll: Job? = null

    /** Démarre le sondage des invitations (à appeler une fois au démarrage de l'app). */
    fun start() {
        if (invitePoll != null) return
        invitePoll = scope.launch {
            while (isActive) {
                refreshInvites()
                delay(INVITE_POLL_MS)
            }
        }
    }

    fun onForeground() {
        scope.launch { refreshInvites() }
    }

    fun offerInvite(link: String) {
        val code = PartyCodes.normalize(link)
        if (code == null) {
            _state.value = PartyState.Failed(PartyFailure.NOT_FOUND)
        } else {
            _invite.value = code
        }
    }

    fun dismissInvite() {
        _invite.value = null
    }

    fun create() = enter(null)

    fun join(input: String) {
        val code = PartyCodes.normalize(input)
        if (code == null) {
            _state.value = PartyState.Failed(PartyFailure.NOT_FOUND)
            return
        }
        enter(code)
    }

    private fun enter(code: String?) {
        val session = account.session.value
        if (session == null) {
            _state.value = PartyState.Failed(PartyFailure.NOT_SIGNED_IN)
            return
        }
        scope.launch {
            leaveRoom()
            _state.value = PartyState.Connecting(code)
            val result = runCatching {
                if (code == null) {
                    account.authorized { client.partyCreate(it) } to session.user.id
                } else {
                    val joined = account.authorized { client.partyJoin(it, code) }
                    when (joined.status) {
                        "ok" -> code to joined.host
                        "full" -> return@launch fail(PartyFailure.FULL)
                        else -> return@launch fail(PartyFailure.NOT_FOUND)
                    }
                }
            }
            val (roomCode, host) = result.getOrElse { return@launch fail(failureOf(it)) }
            val device = java.util.UUID.randomUUID().toString().take(8)
            open(roomCode, PartyMembers.deviceId(session.user.id, device), host, displayName(session.user.email))
        }
    }

    private fun open(code: String, selfId: String, hostId: String?, name: String) {
        val channel = RealtimeChannel(
            config,
            http,
            topic = "party:$code",
            presenceKey = selfId,
            private = true,
            accessToken = { runCatching { account.authorized { it } }.getOrNull() },
        )
        lateinit var created: Room
        _chat.value = emptyList()
        val sync = PartySync(
            selfId,
            clock = ::elapsedMs,
            send = { message -> channel.broadcast(EVENT, PartyCodec.encode(PartyEnvelope(from = selfId, m = message))) },
            notify = { notice -> _notices.tryEmit(notice) },
            onChat = { line -> _chat.value = (_chat.value + line).takeLast(MAX_CHAT_LINES) },
        ).also { it.hostId = hostId }
        val events = scope.launch {
            channel.events.collect { event ->
                when (event) {
                    ChannelEvent.Joined -> {
                        created.connected = true
                        created.tracked = null
                        sync.onJoined()
                        trackPresence(created)
                    }
                    is ChannelEvent.Broadcast -> when (event.event) {
                        EVENT -> PartyCodec.decode(event.payload)?.let { sync.onMessage(it.from, it.m) }
                        VOICE_EVENT -> VoiceCodec.decodeFor(selfId, event.payload)?.let { voice.onSignal(it.from, it.s) }
                    }
                    is ChannelEvent.Presence -> {
                        created.members = event.members.mapNotNull { (id, meta) -> PartyCodec.decodePresence(id, meta) }
                            .sortedBy { it.name.lowercase() }
                        sync.onMembers(created.members)
                        voice.onMembers(created.members.filter { it.voice }.map { it.id })
                    }
                    is ChannelEvent.Disconnected -> created.connected = false
                    is ChannelEvent.Rejected -> {
                        // Annule aussi cette collecte : l'état est publié avant.
                        _state.value = PartyState.Failed(PartyFailure.REJECTED)
                        leaveRoom()
                        return@collect
                    }
                }
                publish()
            }
        }
        val ticker = scope.launch {
            while (true) {
                delay(TICK_MS)
                sync.tick()
                voice.tick()
                trackPresence(created)
                publish()
            }
        }
        created = Room(code, name, sync, channel, listOf(events, ticker))
        room = created
        channel.start(scope)
        publish()
    }

    /** La présence dit si l'on regarde avec la salle et si l'on est au vocal : republiée seulement quand cela change. */
    private fun trackPresence(room: Room) {
        if (!room.connected) return
        val presence = PartyPresence(room.name, room.sync.synced, voice.active, voice.muted)
        if (room.tracked == presence) return
        room.tracked = presence
        room.channel.track(PartyCodec.encodePresence(presence))
    }

    /** Faux si le micro ou WebRTC ne sont pas disponibles sur cet ordinateur. */
    fun joinVoice(): Boolean {
        val r = room ?: return false
        val selfId = r.sync.selfId
        val started = voice.start(selfId) { signal ->
            r.channel.broadcast(VOICE_EVENT, VoiceCodec.encode(VoiceEnvelope(from = selfId, s = signal)))
        }
        if (!started) return false
        voice.onMembers(r.members.filter { it.voice }.map { it.id })
        trackPresence(r)
        publish()
        return true
    }

    fun leaveVoice() {
        voice.stop()
        room?.let(::trackPresence)
        publish()
    }

    fun setMuted(value: Boolean) {
        voice.setMuted(value)
        room?.let(::trackPresence)
        publish()
    }

    fun leave() {
        val code = room?.code
        scope.launch {
            leaveRoom()
            _chat.value = emptyList()
            _state.value = PartyState.Idle
            if (code != null) runCatching { account.authorized { client.partyLeave(it, code) } }
        }
    }

    fun setHostOnly(value: Boolean) {
        room?.sync?.setHostOnly(value)
        publish()
    }

    fun setOffsetMs(value: Long) {
        room?.sync?.setOffsetMs(value)
        publish()
    }

    fun sendChat(text: String): Boolean = room?.sync?.sendChat(text) == true

    /** Invite un compte WatchMate par e-mail (notification in-app chez l'autre, pas de mail). */
    fun inviteByEmail(email: String) {
        val code = (state.value as? PartyState.InRoom)?.code ?: return
        scope.launch {
            val outcome = runCatching {
                val result = account.authorized { client.partyInvite(it, code, email) }
                when (result.status) {
                    "ok" -> PartyInviteOutcome.OK
                    "not_found" -> PartyInviteOutcome.NOT_FOUND
                    "not_member" -> PartyInviteOutcome.NOT_MEMBER
                    "self" -> PartyInviteOutcome.SELF
                    "invalid" -> PartyInviteOutcome.INVALID
                    else -> PartyInviteOutcome.NETWORK
                }
            }.getOrElse { inviteFailureOf(it) }
            _inviteSend.tryEmit(outcome)
        }
    }

    fun acceptInvite(invite: PartyInvite) {
        scope.launch {
            runCatching { account.authorized { client.partyDismissInvite(it, invite.id) } }
            _pendingInvites.value = _pendingInvites.value.filterNot { it.id == invite.id }
            join(invite.code)
        }
    }

    fun dismissPendingInvite(invite: PartyInvite) {
        scope.launch {
            runCatching { account.authorized { client.partyDismissInvite(it, invite.id) } }
            _pendingInvites.value = _pendingInvites.value.filterNot { it.id == invite.id }
        }
    }

    private suspend fun refreshInvites() {
        if (account.session.value == null) {
            _pendingInvites.value = emptyList()
            return
        }
        val list = runCatching { account.authorized { client.partyPendingInvites(it) } }.getOrElse {
            if (it is SupabaseException && (it.status == 404 || "PGRST202" in it.message.orEmpty())) emptyList()
            else return
        }
        _pendingInvites.value = list
    }

    private fun inviteFailureOf(e: Throwable): PartyInviteOutcome = when {
        e is SupabaseException && e.error == SupabaseError.UNAUTHORIZED -> PartyInviteOutcome.NOT_SIGNED_IN
        e is SupabaseException && (e.status == 404 || "PGRST202" in e.message.orEmpty()) -> PartyInviteOutcome.NOT_SET_UP
        else -> PartyInviteOutcome.NETWORK
    }

    /** Média que la salle regarde en ce moment, si on ne le suit pas encore. */
    fun joinableMedia(): PartyMedia? {
        val s = _state.value as? PartyState.InRoom ?: return null
        return s.timeline?.media?.takeIf { !s.synced }
    }

    fun dismissFailure() {
        if (_state.value is PartyState.Failed) _state.value = PartyState.Idle
    }

    private suspend fun leaveRoom() {
        val current = room ?: return
        room = null
        voice.stop()
        current.jobs.forEach { it.cancel() }
        current.channel.close()
    }

    // ---- Lecteur ----

    fun startPositionFor(media: PartyMedia): Long? = room?.sync?.startPositionFor(media)

    fun attach(player: PartyPlayer, media: PartyMedia) {
        room?.sync?.attach(player, media)
        publish()
    }

    fun detach(player: PartyPlayer) {
        room?.sync?.detach(player)
        publish()
    }

    fun onLocalPlayWhenReady(play: Boolean) {
        room?.sync?.onLocalPlayWhenReady(play)
    }

    fun onLocalSeek(positionMs: Long) {
        room?.sync?.onLocalSeek(positionMs)
    }

    fun onLocalBuffering(buffering: Boolean) {
        room?.sync?.onLocalBuffering(buffering)
    }

    private fun publish() {
        val r = room ?: return
        _state.value = PartyState.InRoom(
            code = r.code,
            selfId = r.sync.selfId,
            hostId = r.sync.hostId,
            connected = r.connected,
            members = r.members,
            timeline = r.sync.timeline,
            synced = r.sync.synced,
            waitingFor = r.sync.waitingFor.filter { it != r.sync.selfId },
            hostOnly = r.sync.hostOnly,
            offsetMs = r.sync.offsetMs,
            canControl = r.sync.canControl(),
            isHost = r.sync.isHostAccount(),
            inVoice = voice.active,
            muted = voice.muted,
            voiceConnected = voice.connected.value,
        )
    }

    private fun fail(failure: PartyFailure) {
        _state.value = PartyState.Failed(failure)
    }

    private fun failureOf(e: Throwable): PartyFailure = when {
        e is SupabaseException && e.error == SupabaseError.UNAUTHORIZED -> PartyFailure.NOT_SIGNED_IN
        // Fonction absente : watch_party.sql n'a pas été exécuté (PostgREST répond 404, code PGRST202).
        e is SupabaseException && (e.status == 404 || "PGRST202" in e.message.orEmpty()) -> PartyFailure.NOT_SET_UP
        e is SupabaseException && e.error == SupabaseError.NETWORK -> PartyFailure.NETWORK
        e is IOException -> PartyFailure.NETWORK
        else -> PartyFailure.NETWORK
    }

    private fun displayName(email: String?): String =
        email?.substringBefore('@')?.take(MAX_NAME)?.takeIf { it.isNotBlank() } ?: "?"

    private companion object {
        const val EVENT = "sync"
        const val VOICE_EVENT = "voice"
        const val TICK_MS = 250L
        const val INVITE_POLL_MS = 12_000L
        const val MAX_NAME = 24
        const val MAX_CHAT_LINES = 100
    }
}

/** Horloge monotone, comme `SystemClock.elapsedRealtime()` sur Android. */
internal fun elapsedMs(): Long = System.nanoTime() / 1_000_000
