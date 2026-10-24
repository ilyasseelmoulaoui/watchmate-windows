package app.agregateur.core.party

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Négociation WebRTC du vocal, transportée par le canal de la salle (événement `voice`). Chaque message vise un seul
 * membre (`to`) ; l'audio, lui, passe directement entre les appareils.
 */
@Serializable
sealed interface VoiceSignal {
    val to: String

    @Serializable
    @SerialName("offer")
    data class Offer(override val to: String, val sdp: String) : VoiceSignal

    @Serializable
    @SerialName("answer")
    data class Answer(override val to: String, val sdp: String) : VoiceSignal

    @Serializable
    @SerialName("ice")
    data class Ice(override val to: String, val mid: String?, val index: Int, val candidate: String) : VoiceSignal
}

@Serializable
data class VoiceEnvelope(val v: Int = PROTOCOL_VERSION, val from: String, val s: VoiceSignal)

object VoiceCodec {
    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(envelope: VoiceEnvelope): JsonElement = json.encodeToJsonElement(VoiceEnvelope.serializer(), envelope)

    /** Null si le message est illisible, hors limites, ou destiné à un autre membre. */
    fun decodeFor(selfId: String, element: JsonElement): VoiceEnvelope? {
        val envelope = try {
            json.decodeFromJsonElement(VoiceEnvelope.serializer(), element)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (envelope.v != PROTOCOL_VERSION || envelope.s.to != selfId) return null
        if (envelope.from.length !in 1..MAX_MEMBER_ID || envelope.from == selfId) return null
        val valid = when (val s = envelope.s) {
            is VoiceSignal.Offer -> s.sdp.length in 1..MAX_SDP
            is VoiceSignal.Answer -> s.sdp.length in 1..MAX_SDP
            is VoiceSignal.Ice -> s.candidate.length in 1..MAX_CANDIDATE && (s.mid?.length ?: 0) <= MAX_MID && s.index in 0..MAX_INDEX
        }
        return envelope.takeIf { valid }
    }
}

/** Qui appeler et qui raccrocher : maillage complet entre les membres du vocal, plafonné à [MAX_VOICE_PEERS]. */
object VoiceMesh {
    data class Plan(val open: Set<String>, val close: Set<String>)

    /** Un seul des deux envoie l'offre, pour éviter que deux offres se croisent. */
    fun isOfferer(selfId: String, peerId: String): Boolean = selfId < peerId

    /**
     * [voiceMembers] : membres qui annoncent le vocal dans la présence. [current] : connexions existantes.
     * [cooling] : pairs dont la connexion vient d'échouer, retentés plus tard. On n'ouvre que les connexions dont on
     * est l'offreur ; l'autre côté attend l'offre.
     */
    fun plan(
        selfId: String,
        inVoice: Boolean,
        voiceMembers: Collection<String>,
        current: Set<String>,
        cooling: Set<String> = emptySet(),
    ): Plan {
        if (!inVoice) return Plan(emptySet(), current)
        val wanted = voiceMembers.filter { it != selfId }.distinct().sorted().take(MAX_VOICE_PEERS).toSet()
        val open = wanted.filter { it !in current && it !in cooling && isOfferer(selfId, it) }.toSet()
        return Plan(open, current - wanted)
    }
}

const val MAX_VOICE_PEERS = 5
private const val MAX_MEMBER_ID = 512
private const val MAX_SDP = 32_000
private const val MAX_CANDIDATE = 1_024
private const val MAX_MID = 64
private const val MAX_INDEX = 64
