package app.agregateur.core.party

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Ce que regarde la salle. Seuls les identifiants circulent : chaque appareil trouve sa propre source avec ses modules.
 * `moduleUrl` : module d'où vient la fiche chez celui qui a lancé la lecture (les autres se replient sur les leurs).
 */
@Serializable
data class PartyMedia(
    val type: String,
    val id: String,
    val videoId: String,
    val title: String,
    val poster: String? = null,
    val moduleUrl: String = "",
) {
    internal fun isValid(): Boolean =
        type.length in 1..MAX_ID && id.length in 1..MAX_ID && videoId.length in 1..MAX_ID &&
            title.length <= MAX_TEXT && (poster?.length ?: 0) <= MAX_URL && moduleUrl.length <= MAX_URL
}

/** Même épisode du même titre (le titre affiché et le module d'origine peuvent différer d'un appareil à l'autre). */
fun PartyMedia?.sameAs(other: PartyMedia?): Boolean =
    this != null && other != null && type == other.type && id == other.id && videoId == other.videoId

enum class PartyAction { LOAD, PLAY, PAUSE, SEEK }

@Serializable
sealed interface PartyMessage {
    /** Décision d'un membre : nouvel état de la salle. Ordre total par (`version`, auteur). */
    @Serializable
    @SerialName("cmd")
    data class Command(
        val version: Long,
        val action: PartyAction,
        val media: PartyMedia,
        val positionMs: Long,
        val playing: Boolean,
    ) : PartyMessage

    /** Position réelle du meneur (toutes les 5 s pendant la lecture), ou réponse à [Hello]. `by` : auteur de la version. */
    @Serializable
    @SerialName("state")
    data class State(
        val version: Long,
        val by: String,
        val media: PartyMedia,
        val positionMs: Long,
        val playing: Boolean,
    ) : PartyMessage

    /** Arrivée ou reconnexion : demande l'état de la salle. */
    @Serializable
    @SerialName("hello")
    data object Hello : PartyMessage

    /**
     * `on` : l'expéditeur charge (lecture bloquée) ; les autres l'attendent un temps limité, arrêtés à `positionMs`
     * (là où il est bloqué), pour repartir tous du même point.
     */
    @Serializable
    @SerialName("wait")
    data class Wait(val on: Boolean, val positionMs: Long? = null) : PartyMessage

    @Serializable
    @SerialName("ping")
    data class Ping(val n: Int) : PartyMessage

    @Serializable
    @SerialName("pong")
    data class Pong(val to: String, val n: Int) : PartyMessage

    /** Chat de la salle (phase 4) : texte brut, jamais interprété comme balises. */
    @Serializable
    @SerialName("chat")
    data class Chat(val text: String) : PartyMessage

    /** Réglages de salle : seul l'hôte peut les publier. `hostOnly` : seuls ses appareils pilotent. */
    @Serializable
    @SerialName("config")
    data class Config(val hostOnly: Boolean) : PartyMessage
}

/** Une ligne de chat reçue ou envoyée. */
data class PartyChatLine(val from: String, val text: String, val atMs: Long)

/** Message tel qu'il circule. `from` est déclaré par l'expéditeur : les membres d'une salle se font confiance. */
@Serializable
data class PartyEnvelope(val v: Int = PROTOCOL_VERSION, val from: String, val m: PartyMessage)

/**
 * Ce que chaque membre publie dans la présence de la salle. `watching` : il lit ce que regarde la salle ; `voice` : il
 * a rejoint le vocal ; `muted` : son micro est coupé.
 */
@Serializable
data class PartyPresence(val name: String, val watching: Boolean = false, val voice: Boolean = false, val muted: Boolean = false)

data class PartyMember(val id: String, val name: String, val watching: Boolean, val voice: Boolean = false, val muted: Boolean = false) {
    val userId: String get() = PartyMembers.userOf(id)
}

/** Un membre est un appareil : « compte/appareil », pour que deux appareils du même compte se voient. */
object PartyMembers {
    fun deviceId(userId: String, device: String): String = "$userId/$device"

    fun userOf(memberId: String): String = memberId.substringBefore('/')
}

object PartyCodec {
    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(envelope: PartyEnvelope): JsonElement = json.encodeToJsonElement(PartyEnvelope.serializer(), envelope)

    /** Null si le message est illisible, d'une autre version du protocole ou hors limites : il est ignoré. */
    fun decode(element: JsonElement): PartyEnvelope? {
        val envelope = try {
            json.decodeFromJsonElement(PartyEnvelope.serializer(), element)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (envelope.v != PROTOCOL_VERSION || envelope.from.length !in 1..MAX_ID) return null
        val valid = when (val m = envelope.m) {
            is PartyMessage.Command -> m.media.isValid() && m.positionMs >= 0 && m.version > 0
            is PartyMessage.State -> m.media.isValid() && m.positionMs >= 0 && m.by.length in 1..MAX_ID
            is PartyMessage.Pong -> m.to.length in 1..MAX_ID
            is PartyMessage.Chat -> m.text.length in 1..MAX_CHAT
            is PartyMessage.Config -> true
            else -> true
        }
        return envelope.takeIf { valid }
    }

    fun encodePresence(presence: PartyPresence): JsonObject =
        json.encodeToJsonElement(PartyPresence.serializer(), presence) as JsonObject

    fun decodePresence(id: String, element: JsonElement): PartyMember? = try {
        val p = json.decodeFromJsonElement(PartyPresence.serializer(), element)
        PartyMember(id, p.name.take(MAX_NAME), p.watching, p.voice, p.muted)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Codes de salle : 8 caractères sans ceux qu'on confond (I, O, 0, 1), comme ceux créés par `party_create`. */
object PartyCodes {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Accepte « abcd-2345 », « ABCD 2345 » ou un lien `watchmate://party/ABCD2345` ; null si ce n'est pas un code. */
    fun normalize(input: String): String? {
        val raw = input.trim().substringAfterLast('/').uppercase().filter { it != '-' && it != ' ' }
        return raw.takeIf { it.length == 8 && it.all { c -> c in ALPHABET } }
    }

    fun display(code: String): String = if (code.length == 8) "${code.take(4)}-${code.drop(4)}" else code

    fun link(code: String): String = "watchmate://party/$code"
}

const val PROTOCOL_VERSION = 1
const val MAX_CHAT = 280
const val MAX_OFFSET_MS = 30_000L
private const val MAX_ID = 512
private const val MAX_URL = 2048
private const val MAX_TEXT = 300
private const val MAX_NAME = 40
