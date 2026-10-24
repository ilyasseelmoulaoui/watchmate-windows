package app.agregateur.core.party

import kotlin.math.abs

/** Ce dont la synchronisation a besoin du lecteur local. */
interface PartyPlayer {
    val positionMs: Long
    val playWhenReady: Boolean
    fun setPlayWhenReady(play: Boolean)
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
}

/** État de la salle tel que ce membre le connaît. `atMs` : horloge locale au moment où `positionMs` était juste. */
data class PartyTimeline(
    val version: Long,
    val by: String,
    val media: PartyMedia,
    val positionMs: Long,
    val playing: Boolean,
    val atMs: Long,
)

/** Ce qu'un autre membre vient de faire, pour l'afficher. */
data class PartyNotice(val from: String, val action: PartyAction, val media: PartyMedia, val positionMs: Long)

/**
 * Synchronisation d'une salle, sans réseau ni Android : appelée sur un seul fil, l'horloge et l'envoi sont fournis.
 *
 * - Toute décision (lancer, lire, pause, saut) est une commande versionnée ; la plus grande (version, auteur) l'emporte,
 *   donc deux décisions simultanées aboutissent au même état chez tout le monde.
 * - Un membre (l'hôte s'il regarde, sinon le plus petit identifiant parmi ceux qui regardent) publie sa position
 *   toutes les 5 s ; les autres corrigent leur écart (vitesse légèrement modifiée, ou saut si l'écart est grand).
 * - Les retards du réseau sont estimés par ping/pong (moitié de l'aller-retour), sans comparer les horloges.
 * - Un membre bloqué par le chargement fait attendre les autres, 10 s au plus.
 * - Une action locale qui ne fait que refléter l'état de la salle (écho de ce qu'on vient d'appliquer) n'est pas renvoyée.
 */
class PartySync(
    /** Identifiant de cet appareil dans la salle ([PartyMembers.deviceId]) : un même compte peut être sur deux appareils. */
    val selfId: String,
    private val clock: () -> Long,
    private val send: (PartyMessage) -> Unit,
    private val notify: (PartyNotice) -> Unit = {},
    private val onChat: (PartyChatLine) -> Unit = {},
) {
    /** Compte de l'hôte (pas un appareil) : n'importe lequel de ses appareils qui regarde peut mener. */
    var hostId: String? = null

    /** Si vrai, seuls les appareils de l'hôte publient des commandes (phase 4). */
    var hostOnly: Boolean = false
        private set

    /**
     * Décalage local permanent (±[MAX_OFFSET_MS]) pour compenser des génériques différents entre sources.
     * N'est pas diffusé : chaque appareil règle le sien.
     */
    var offsetMs: Long = 0
        private set

    var timeline: PartyTimeline? = null
        private set

    /** Lecteur local et ce qu'il lit ; il ne suit la salle que s'il lit la même chose. */
    private var player: PartyPlayer? = null
    private var playerMedia: PartyMedia? = null

    private var members: Map<String, PartyMember> = emptyMap()
    private val waiting = mutableMapOf<String, Long>()
    private var frozen = false
    private val latency = mutableMapOf<String, Long>()
    private val pings = mutableMapOf<Int, Long>()
    private var nextPing = 0
    private var lastPingAt: Long? = null
    private var lastHeartbeatAt = 0L
    private var everReady = false

    /** Le lecteur local charge (après l'ouverture ou un saut) : on ne le fait pas sauter à nouveau pendant ce temps. */
    private var localBuffering = false

    /** Membres dont l'attente a expiré : leurs nouvelles demandes d'attente sont ignorées un moment. */
    private val waitBlockedUntil = mutableMapOf<String, Long>()
    private var bufferingSince: Long? = null
    /** Écart à la cible au moment où le chargement a commencé ; sert à distinguer rattrapage et vrai blocage. */
    private var bufferingStartGapMs: Long? = null
    private var sentWait = false
    private var speed = 1f
    /** Vrai pendant [apply] : les rappels du lecteur (pause/saut) ne doivent pas être renvoyés comme actions. */
    private var applying = false

    /**
     * Après un attach (surtout mid-film / torrent), éviter les hard-seek du meneur le temps que la source
     * locale charge : un saut trop tôt provoque souvent ERROR_CODE_DECODING_FAILED.
     */
    private var hardSeekAfter = 0L

    /** Vrai si le lecteur local suit la salle. */
    val synced: Boolean get() = player != null && playerMedia.sameAs(timeline?.media)

    val waitingFor: Set<String> get() = waiting.keys.toSet()

    fun latencyTo(memberId: String): Long = latency[memberId] ?: DEFAULT_LATENCY_MS

    fun isHostAccount(): Boolean = hostId != null && PartyMembers.userOf(selfId) == hostId

    fun canControl(): Boolean = !hostOnly || isHostAccount()

    fun setHostOnly(value: Boolean) {
        if (!isHostAccount()) return
        if (hostOnly == value) return
        hostOnly = value
        send(PartyMessage.Config(value))
    }

    fun setOffsetMs(value: Long) {
        val clamped = value.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        if (offsetMs == clamped) return
        offsetMs = clamped
        apply(seek = true)
    }

    fun sendChat(text: String): Boolean {
        val trimmed = text.trim().take(MAX_CHAT)
        if (trimmed.isEmpty()) return false
        send(PartyMessage.Chat(trimmed))
        onChat(PartyChatLine(selfId, trimmed, clock()))
        return true
    }

    /** Connexion (ou reconnexion) à la salle : on demande l'état et on mesure les retards. */
    fun onJoined() {
        send(PartyMessage.Hello)
        if (isHostAccount() && hostOnly) send(PartyMessage.Config(true))
        ping()
    }

    fun onMembers(list: List<PartyMember>) {
        members = list.associateBy { it.id }
        val gone = waiting.keys.filter { it != selfId && it !in members }
        if (gone.isNotEmpty()) {
            gone.forEach(waiting::remove)
            refreshFreeze()
            apply()
        }
    }

    fun isLeader(): Boolean = leaderId() == selfId

    fun leaderId(): String? {
        val watchers = members.values.filter { it.watching }.map { it.id }.toMutableSet()
        if (synced) watchers += selfId
        val hostDevices = watchers.filter { PartyMembers.userOf(it) == hostId }
        return (hostDevices.ifEmpty { watchers }).minOrNull()
    }

    /** Position partagée de la salle (sans décalage local). */
    fun roomPositionNow(): Long? {
        val t = timeline ?: return null
        return if (t.playing && !frozen) t.positionMs + (clock() - t.atMs) else t.positionMs
    }

    /** Position cible du lecteur local (salle + décalage manuel). */
    fun positionNow(): Long? = roomPositionNow()?.let { (it + offsetMs).coerceAtLeast(0L) }

    /** Position de départ pour ouvrir [media] en rejoignant la salle ; null si la salle regarde autre chose. */
    fun startPositionFor(media: PartyMedia): Long? = positionNow()?.takeIf { timeline?.media.sameAs(media) }

    fun effectivePlaying(): Boolean = timeline?.playing == true && waiting.isEmpty()

    // ---- Lecteur local ----

    /** Le lecteur local commence [media] : il rejoint la salle si elle regarde la même chose, sinon il la fait changer. */
    fun attach(player: PartyPlayer, media: PartyMedia) {
        this.player = player
        playerMedia = media
        everReady = false
        localBuffering = true
        bufferingSince = null
        bufferingStartGapMs = null
        sentWait = false
        hardSeekAfter = clock() + JOIN_HARD_SEEK_GRACE_MS
        if (timeline?.media.sameAs(media)) {
            apply(seek = true)
        } else {
            issue(PartyAction.LOAD, media, player.positionMs, playing = true)
        }
        // Chargement initial : armé même si ExoPlayer a signalé BUFFERING avant/après selon l'ordre des appels.
        noteLocalBufferingStarted()
    }

    fun detach(player: PartyPlayer) {
        if (this.player !== player) return
        if (sentWait) {
            send(PartyMessage.Wait(false))
            waiting.remove(selfId)
            refreshFreeze()
        }
        this.player = null
        playerMedia = null
        sentWait = false
        bufferingSince = null
        speed = 1f
    }

    fun onLocalPlayWhenReady(play: Boolean) {
        if (applying) return
        val p = player ?: return
        if (!synced) return
        // Pause locale : ne pas garder la salle bloquée sur notre ancien « wait ».
        // Après avoir levé l'attente, effectivePlaying repasse à true : il faut quand même publier PAUSE.
        if (!play && (sentWait || selfId in waiting)) {
            clearLocalWait()
            issue(PartyAction.PAUSE, timeline!!.media, p.positionMs, playing = false)
            return
        }
        if (play == effectivePlaying()) return
        issue(if (play) PartyAction.PLAY else PartyAction.PAUSE, timeline!!.media, p.positionMs, play)
    }

    fun onLocalSeek(positionMs: Long) {
        if (applying) return
        if (!synced) return
        val expected = positionNow() ?: return
        if (abs(positionMs - expected) < SEEK_ECHO_MS) return
        issue(PartyAction.SEEK, timeline!!.media, positionMs, timeline!!.playing)
    }

    fun onLocalBuffering(buffering: Boolean) {
        if (!synced) return
        localBuffering = buffering
        if (buffering) {
            noteLocalBufferingStarted()
            return
        }
        everReady = true
        bufferingSince = null
        bufferingStartGapMs = null
        if (sentWait) {
            clearLocalWait(resume = true)
            // Évite la boucle charge → wait → reprise → re-charge → wait.
            waitBlockedUntil[selfId] = clock() + WAIT_RECOVERY_MS
        }
    }

    /** Arme le délai avant Wait : chargement initial inclus (plus seulement après le premier READY). */
    private fun noteLocalBufferingStarted() {
        if (!localBuffering || !synced || !effectivePlaying()) return
        if (bufferingSince != null) return
        bufferingSince = clock()
        val target = positionNow()
        // Positif = en retard sur la cible (rattrapage) ; négatif/zéro = bloqué au bon endroit.
        bufferingStartGapMs = if (target == null) 0L else target - player!!.positionMs
    }

    // ---- Réseau ----

    fun onMessage(from: String, message: PartyMessage) {
        if (from == selfId) return
        when (message) {
            is PartyMessage.Command -> {
                if (hostOnly && PartyMembers.userOf(from) != hostId) return
                if (!newer(message.version, from)) return
                timeline = PartyTimeline(
                    message.version,
                    from,
                    message.media,
                    message.positionMs + if (message.playing) latencyTo(from) else 0,
                    message.playing,
                    clock(),
                )
                // Une décision explicite passe avant l'attente de ceux qui chargent.
                waiting.clear()
                frozen = false
                lastHeartbeatAt = clock()
                notify(PartyNotice(from, message.action, message.media, message.positionMs))
                apply(seek = true)
            }
            is PartyMessage.State -> {
                val current = timeline
                val sameVersion = current != null && current.version == message.version && current.by == message.by
                if (!sameVersion && !newer(message.version, message.by)) return
                val position = message.positionMs + if (message.playing) latencyTo(from) else 0
                timeline = PartyTimeline(message.version, message.by, message.media, position, message.playing, clock())
                // Pas de saut ici : les heartbeats du meneur corrigent via [correctDrift], sinon une source
                // lente (coût de saut) boucle charge → saut → charge.
                apply(seek = false)
            }
            PartyMessage.Hello -> stateMessage()?.let(send)
            is PartyMessage.Wait -> {
                val blocked = (waitBlockedUntil[from] ?: Long.MIN_VALUE) > clock()
                if (message.on && blocked) return
                if (message.on) waiting[from] = clock() + WAIT_MAX_MS else waiting.remove(from)
                refreshFreeze()
                if (message.on) {
                    message.positionMs?.let(::holdAt)
                    // Reculer jusqu'au membre bloqué : le meneur sinon repartirait en avance.
                    apply(seek = true)
                } else {
                    apply(seek = false)
                }
            }
            is PartyMessage.Ping -> send(PartyMessage.Pong(from, message.n))
            is PartyMessage.Pong -> {
                if (message.to != selfId) return
                val sentAt = pings[message.n] ?: return
                val oneWay = ((clock() - sentAt) / 2).coerceIn(0, MAX_LATENCY_MS)
                latency[from] = latency[from]?.let { (it * 3 + oneWay) / 4 } ?: oneWay
            }
            is PartyMessage.Chat -> {
                val text = message.text.trim().take(MAX_CHAT)
                if (text.isNotEmpty()) onChat(PartyChatLine(from, text, clock()))
            }
            is PartyMessage.Config -> {
                if (PartyMembers.userOf(from) != hostId) return
                hostOnly = message.hostOnly
            }
        }
    }

    /** À appeler régulièrement (toutes les 250 à 500 ms). */
    fun tick() {
        val now = clock()
        val expired = waiting.filterValues { it <= now }.keys
        if (expired.isNotEmpty()) {
            expired.forEach {
                waiting.remove(it)
                waitBlockedUntil[it] = now + WAIT_COOLDOWN_MS
            }
            if (selfId in expired) {
                sentWait = false
                bufferingSince = null
            }
            refreshFreeze()
            apply()
        }
        val since = bufferingSince
        val selfBlocked = (waitBlockedUntil[selfId] ?: Long.MIN_VALUE) > now
        val pBuf = player
        // En retard au début du chargement → rattrapage seul ; ne pas figer la salle.
        val catchingUp = (bufferingStartGapMs ?: 0L) > CATCHING_UP_MS
        if (catchingUp) {
            bufferingSince = null
        } else if (since != null && !sentWait && !selfBlocked && synced && effectivePlaying() && now - since >= WAIT_AFTER_MS) {
            sentWait = true
            waiting[selfId] = now + WAIT_MAX_MS
            val stuckAt = pBuf?.let { (it.positionMs - offsetMs).coerceAtLeast(0L) }
            send(PartyMessage.Wait(true, stuckAt))
            refreshFreeze()
            stuckAt?.let(::holdAt)
            apply(seek = true)
        }
        if (lastPingAt.let { it == null || now - it >= PING_INTERVAL_MS }) ping()
        val p = player ?: return
        if (!synced) return
        if (isLeader()) {
            if (effectivePlaying() && now - lastHeartbeatAt >= HEARTBEAT_MS) {
                lastHeartbeatAt = now
                val roomPos = (p.positionMs - offsetMs).coerceAtLeast(0L)
                timeline = timeline!!.copy(positionMs = roomPos, atMs = now)
                stateMessage()?.let(send)
            }
            setSpeed(1f)
        } else if (!localBuffering && everReady) {
            correctDrift(p)
        }
    }

    private fun correctDrift(p: PartyPlayer) {
        val target = positionNow() ?: return
        val drift = p.positionMs - target
        if (!effectivePlaying()) {
            if (abs(drift) > APPLY_SEEK_MS && clock() >= hardSeekAfter) p.seekTo(target)
            setSpeed(1f)
            return
        }
        when {
            abs(drift) > HARD_SEEK_MS -> {
                if (clock() < hardSeekAfter) {
                    // Grace : rattrapage doux seulement (évite décodage raté sur seek torrent trop tôt).
                    setSpeed(if (drift > 0) SLOWER else FASTER)
                } else {
                    p.seekTo(target + if (p.playWhenReady) SEEK_LEAD_MS else 0)
                    hardSeekAfter = clock() + HARD_SEEK_COOLDOWN_MS
                    setSpeed(1f)
                }
            }
            abs(drift) > SOFT_DRIFT_MS -> setSpeed(if (drift > 0) SLOWER else FASTER)
            abs(drift) < DRIFT_OK_MS -> setSpeed(1f)
        }
    }

    private fun setSpeed(value: Float) {
        if (speed == value) return
        speed = value
        player?.setSpeed(value)
    }

    /**
     * Aligne lecture/pause sur la salle. Un saut n'a lieu que si [seek] est demandé (commande, attach, offset) :
     * les heartbeats passent par [correctDrift] pour ne pas relancer le chargement d'une source lente.
     */
    private fun apply(seek: Boolean = false) {
        val p = player ?: return
        if (!synced) return
        val target = positionNow() ?: return
        applying = true
        try {
            if (seek && abs(p.positionMs - target) > APPLY_SEEK_MS) p.seekTo(target)
            val want = effectivePlaying()
            if (!want) setSpeed(1f)
            if (p.playWhenReady != want) p.setPlayWhenReady(want)
        } finally {
            applying = false
        }
    }

    private fun clearLocalWait(resume: Boolean = false) {
        if (!sentWait && selfId !in waiting) {
            bufferingSince = null
            bufferingStartGapMs = null
            return
        }
        sentWait = false
        bufferingSince = null
        bufferingStartGapMs = null
        if (selfId in waiting) {
            send(PartyMessage.Wait(false))
            waiting.remove(selfId)
            refreshFreeze()
            if (resume) apply()
        }
    }

    private fun issue(action: PartyAction, media: PartyMedia, positionMs: Long, playing: Boolean) {
        if (!canControl()) {
            // Remet le lecteur sur l'état de la salle : l'action locale n'a pas été acceptée.
            apply(seek = true)
            return
        }
        val now = clock()
        val version = (timeline?.version ?: 0) + 1
        // Les commandes circulent sans le décalage local (propre à chaque appareil).
        val roomPosition = (positionMs - offsetMs).coerceAtLeast(0L)
        timeline = PartyTimeline(version, selfId, media, roomPosition, playing, now)
        waiting.clear()
        frozen = false
        sentWait = false
        bufferingSince = null
        lastHeartbeatAt = now
        send(PartyMessage.Command(version, action, media, roomPosition, playing))
    }

    /** Pendant l'attente, la position de la salle est figée : sinon tout le monde sauterait en avant à la reprise. */
    private fun refreshFreeze() {
        val t = timeline ?: return
        val shouldFreeze = waiting.isNotEmpty()
        if (shouldFreeze == frozen) return
        val now = clock()
        timeline = if (shouldFreeze) {
            t.copy(positionMs = roomPositionNow()!!, atMs = now)
        } else {
            t.copy(atMs = now)
        }
        frozen = shouldFreeze
    }

    /** Pendant l'attente, la salle s'arrête là où le plus en retard de ceux qu'on attend est bloqué. */
    private fun holdAt(positionMs: Long) {
        val t = timeline ?: return
        if (!frozen || !t.playing) return
        if (positionMs < t.positionMs) timeline = t.copy(positionMs = positionMs)
    }

    private fun stateMessage(): PartyMessage.State? {
        val t = timeline ?: return null
        val position = if (synced) (player!!.positionMs - offsetMs).coerceAtLeast(0L) else roomPositionNow()!!
        return PartyMessage.State(t.version, t.by, t.media, position, t.playing)
    }

    private fun newer(version: Long, by: String): Boolean {
        val t = timeline ?: return true
        return version > t.version || (version == t.version && by > t.by)
    }

    private fun ping() {
        val n = nextPing++
        lastPingAt = clock()
        pings[n] = clock()
        pings.keys.filter { it < n - 4 }.forEach(pings::remove)
        send(PartyMessage.Ping(n))
    }

    companion object {
        const val DEFAULT_LATENCY_MS = 100L
        const val MAX_LATENCY_MS = 2_000L
        const val HEARTBEAT_MS = 5_000L
        const val PING_INTERVAL_MS = 30_000L
        /** Plus long : un micro-gel (FPS bas, torrent) ne doit pas figer toute la salle. */
        const val WAIT_AFTER_MS = 3_000L
        const val WAIT_MAX_MS = 8_000L
        const val WAIT_COOLDOWN_MS = 30_000L
        /** Après une attente résolue, ne pas redemander tout de suite (boucle charge/attente). */
        const val WAIT_RECOVERY_MS = 10_000L
        /** Au-delà, on rattrape : on ne bloque pas les autres. */
        const val CATCHING_UP_MS = 2_000L
        const val SEEK_ECHO_MS = 2_000L
        const val APPLY_SEEK_MS = 2_000L
        const val HARD_SEEK_MS = 4_000L
        const val SOFT_DRIFT_MS = 500L
        const val DRIFT_OK_MS = 150L
        /** Pas de hard-seek pendant ce délai après attach (source lente / torrent). */
        const val JOIN_HARD_SEEK_GRACE_MS = 25_000L
        /** Entre deux hard-seek : laisse le torrent charger la nouvelle fenêtre. */
        const val HARD_SEEK_COOLDOWN_MS = 12_000L

        /** Un saut prend un peu de temps : viser légèrement en avant évite de rester en retard après. */
        const val SEEK_LEAD_MS = 200L
        const val SLOWER = 0.95f
        const val FASTER = 1.05f
    }
}
