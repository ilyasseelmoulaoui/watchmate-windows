package app.agregateur.party

import app.agregateur.BuildConfig
import app.agregateur.core.party.MAX_VOICE_PEERS
import app.agregateur.core.party.VoiceMesh
import app.agregateur.core.party.VoiceSignal
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Vocal de la salle : une connexion WebRTC audio par autre membre du vocal (maillage), comme sur Android. La
 * négociation passe par le canal de la salle ; l'audio va directement d'un appareil à l'autre. Tout l'état vit
 * sur le fil de l'interface, les rappels WebRTC y sont renvoyés.
 */
class VoiceChat {
    private var factory: PeerConnectionFactory? = null
    private var source: AudioTrackSource? = null
    private var track: AudioTrack? = null
    private var selfId: String? = null
    private var send: (VoiceSignal) -> Unit = {}
    private val peers = mutableMapOf<String, Peer>()
    private val cooling = mutableMapOf<String, Long>()
    private var voiceMembers: List<String> = emptyList()

    private val _connected = MutableStateFlow<Set<String>>(emptySet())
    val connected: StateFlow<Set<String>> = _connected.asStateFlow()

    val active: Boolean get() = selfId != null
    var muted = false
        private set

    private class Peer(val id: String, val createdAt: Long) {
        lateinit var pc: RTCPeerConnection
        var remoteSet = false
        var connected = false
        val pending = mutableListOf<RTCIceCandidate>()
    }

    /** Faux si le micro ou la bibliothèque WebRTC ne sont pas disponibles sur cet ordinateur. */
    fun start(selfId: String, send: (VoiceSignal) -> Unit): Boolean {
        if (active) return true
        val created = runCatching {
            val f = factory()
            val options = AudioOptions().apply {
                echoCancellation = true
                noiseSuppression = true
                autoGainControl = true
                highpassFilter = true
            }
            val s = f.createAudioSource(options)
            s to f.createAudioTrack("mic", s).also { it.isEnabled = !muted }
        }.getOrElse {
            System.err.println("Vocal indisponible : $it")
            return false
        }
        source = created.first
        track = created.second
        this.selfId = selfId
        this.send = send
        reconcile()
        return true
    }

    fun stop() {
        if (!active) return
        peers.keys.toList().forEach { drop(it, cool = false) }
        cooling.clear()
        track?.dispose()
        track = null
        source = null
        selfId = null
        send = {}
        publishConnected()
    }

    fun setMuted(value: Boolean) {
        muted = value
        track?.isEnabled = !value
    }

    fun onMembers(voiceMemberIds: List<String>) {
        voiceMembers = voiceMemberIds
        reconcile()
    }

    fun tick() {
        if (!active) return
        val now = elapsedMs()
        peers.values.filter { !it.connected && now - it.createdAt > CONNECT_TIMEOUT_MS }.forEach { drop(it.id, cool = true) }
        reconcile()
    }

    fun onSignal(from: String, signal: VoiceSignal) {
        if (!active) return
        when (signal) {
            is VoiceSignal.Offer -> {
                drop(from, cool = false)
                if (peers.size >= MAX_VOICE_PEERS) return
                val peer = create(from) ?: return
                peer.pc.setRemoteDescription(
                    RTCSessionDescription(RTCSdpType.OFFER, signal.sdp),
                    setObserver(peer) {
                        remoteReady(peer)
                        peer.pc.createAnswer(RTCAnswerOptions(), createObserver(peer) { answer ->
                            peer.pc.setLocalDescription(answer, setObserver(peer) { send(VoiceSignal.Answer(from, answer.sdp)) })
                        })
                    },
                )
            }
            is VoiceSignal.Answer -> {
                val peer = peers[from] ?: return
                peer.pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.ANSWER, signal.sdp), setObserver(peer) { remoteReady(peer) })
            }
            is VoiceSignal.Ice -> {
                val peer = peers[from] ?: return
                val candidate = RTCIceCandidate(signal.mid, signal.index, signal.candidate)
                if (peer.remoteSet) peer.pc.addIceCandidate(candidate) else peer.pending += candidate
            }
        }
    }

    private fun reconcile() {
        val now = elapsedMs()
        cooling.entries.removeAll { now - it.value > COOLDOWN_MS }
        val self = selfId
        val plan = VoiceMesh.plan(self.orEmpty(), self != null, voiceMembers, peers.keys, cooling.keys)
        // Une offre peut arriver avant la présence qui annonce son auteur : on laisse un délai avant de raccrocher.
        plan.close.filter { self == null || now - (peers[it]?.createdAt ?: 0L) > PRESENCE_GRACE_MS }.forEach { drop(it, cool = false) }
        if (self == null) return
        plan.open.forEach(::call)
    }

    private fun call(id: String) {
        val peer = create(id) ?: return
        peer.pc.createOffer(RTCOfferOptions(), createObserver(peer) { offer ->
            peer.pc.setLocalDescription(offer, setObserver(peer) { send(VoiceSignal.Offer(id, offer.sdp)) })
        })
    }

    private fun create(id: String): Peer? {
        val mic = track ?: return null
        val peer = Peer(id, elapsedMs())
        val pc = runCatching { factory().createPeerConnection(rtcConfig(), observer(peer)) }.getOrNull() ?: return null
        pc.addTrack(mic, listOf(STREAM_ID))
        peer.pc = pc
        peers[id] = peer
        return peer
    }

    private fun remoteReady(peer: Peer) {
        peer.remoteSet = true
        peer.pending.forEach(peer.pc::addIceCandidate)
        peer.pending.clear()
    }

    private fun drop(id: String, cool: Boolean) {
        val peer = peers.remove(id) ?: return
        runCatching { peer.pc.close() }
        if (cool) cooling[id] = elapsedMs()
        publishConnected()
    }

    private fun publishConnected() {
        _connected.value = peers.values.filter { it.connected }.map { it.id }.toSet()
    }

    private fun isCurrent(peer: Peer) = peers[peer.id] === peer

    private fun main(block: () -> Unit) = SwingUtilities.invokeLater(block)

    private fun createObserver(peer: Peer, onCreate: (RTCSessionDescription) -> Unit) = object : CreateSessionDescriptionObserver {
        override fun onSuccess(description: RTCSessionDescription) = main { if (isCurrent(peer)) onCreate(description) }
        override fun onFailure(error: String?) = main { if (isCurrent(peer)) drop(peer.id, cool = true) }
    }

    private fun setObserver(peer: Peer, onSet: () -> Unit) = object : SetSessionDescriptionObserver {
        override fun onSuccess() = main { if (isCurrent(peer)) onSet() }
        override fun onFailure(error: String?) = main { if (isCurrent(peer)) drop(peer.id, cool = true) }
    }

    private fun observer(peer: Peer) = object : PeerConnectionObserver {
        override fun onIceCandidate(candidate: RTCIceCandidate) = main {
            if (isCurrent(peer)) send(VoiceSignal.Ice(peer.id, candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
        }

        override fun onConnectionChange(state: RTCPeerConnectionState) = main {
            if (!isCurrent(peer)) return@main
            when (state) {
                RTCPeerConnectionState.CONNECTED -> peer.connected = true
                RTCPeerConnectionState.DISCONNECTED -> peer.connected = false
                RTCPeerConnectionState.FAILED -> drop(peer.id, cool = true)
                else -> Unit
            }
            publishConnected()
        }
    }

    private fun factory(): PeerConnectionFactory = factory ?: PeerConnectionFactory().also { factory = it }

    private fun rtcConfig(): RTCConfiguration = RTCConfiguration().apply {
        iceServers = buildList {
            add(RTCIceServer().apply { urls = listOf(STUN_URL) })
            if (BuildConfig.TURN_URL.isNotBlank()) {
                add(
                    RTCIceServer().apply {
                        urls = BuildConfig.TURN_URL.split(',').map { it.trim() }
                        username = BuildConfig.TURN_USERNAME
                        password = BuildConfig.TURN_CREDENTIAL
                    },
                )
            }
        }
    }

    private companion object {
        const val STREAM_ID = "voice"
        const val STUN_URL = "stun:stun.l.google.com:19302"
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val COOLDOWN_MS = 10_000L
        const val PRESENCE_GRACE_MS = 5_000L
    }
}
