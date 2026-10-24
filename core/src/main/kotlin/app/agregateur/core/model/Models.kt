package app.agregateur.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class Manifest(
    val id: String,
    val name: String,
    val version: String = "",
    val description: String = "",
    val resources: List<ResourceDecl> = emptyList(),
    val types: List<String> = emptyList(),
    val idPrefixes: List<String>? = null,
    val catalogs: List<CatalogDecl> = emptyList(),
    val logo: String? = null,
    val background: String? = null,
    val behaviorHints: ManifestHints = ManifestHints(),
)

@Serializable
data class ManifestHints(
    val adult: Boolean = false,
    val p2p: Boolean = false,
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false,
)

/** Une ressource déclarée : sous forme de texte (hérite des types et préfixes du manifeste) ou d'objet. */
@Serializable(with = ResourceDeclSerializer::class)
data class ResourceDecl(
    val name: String,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
    val inherits: Boolean = false,
)

object ResourceDeclSerializer : KSerializer<ResourceDecl> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ResourceDecl")

    override fun deserialize(decoder: Decoder): ResourceDecl {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> ResourceDecl(name = element.content, inherits = true)
            is JsonObject -> ResourceDecl(
                name = element.getValue("name").jsonPrimitive.content,
                types = element["types"]?.stringList(),
                idPrefixes = element["idPrefixes"]?.stringList(),
            )
            else -> error("Ressource de module invalide")
        }
    }

    override fun serialize(encoder: Encoder, value: ResourceDecl) {
        val json = encoder as JsonEncoder
        if (value.inherits) {
            json.encodeJsonElement(JsonPrimitive(value.name))
        } else {
            json.encodeJsonElement(
                buildJsonObject {
                    put("name", value.name)
                    value.types?.let { put("types", JsonArray(it.map(::JsonPrimitive))) }
                    value.idPrefixes?.let { put("idPrefixes", JsonArray(it.map(::JsonPrimitive))) }
                },
            )
        }
    }

    private fun JsonElement.stringList(): List<String>? =
        (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

@Serializable
data class CatalogDecl(
    val type: String,
    val id: String,
    val name: String = "",
    val extra: List<ExtraDecl> = emptyList(),
)

@Serializable
data class ExtraDecl(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String>? = null,
    val optionsLimit: Int = 1,
)

@Serializable
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val posterShape: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
data class Meta(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val videos: List<Video> = emptyList(),
)

@Serializable
data class Video(
    val id: String,
    val title: String? = null,
    /** Certains modules (Cinemeta) nomment l'épisode par `name` plutôt que `title`. */
    val name: String? = null,
    val released: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
) {
    val displayTitle: String? get() = title?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
}

@Serializable
data class Stream(
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val nzbUrl: String? = null,
    val rarUrls: List<JsonElement>? = null,
    val zipUrls: List<JsonElement>? = null,
    @SerialName("7zipUrls") val sevenZipUrls: List<JsonElement>? = null,
    val tgzUrls: List<JsonElement>? = null,
    val tarUrls: List<JsonElement>? = null,
    /** Fichier à lire dans une archive, au format d'une expression régulière JavaScript. */
    val fileMustInclude: String? = null,
    /** Pour un torrent : trackers (`tracker:<adresse>`) et nœuds DHT (`dht:<…>`). */
    val sources: List<String>? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val subtitles: List<Subtitle> = emptyList(),
    val behaviorHints: StreamHints = StreamHints(),
)

@Serializable
data class StreamHints(
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val proxyHeaders: ProxyHeaders? = null,
)

@Serializable
data class ProxyHeaders(
    val request: Map<String, String> = emptyMap(),
    val response: Map<String, String> = emptyMap(),
)

@Serializable
data class Subtitle(
    val id: String = "",
    val url: String,
    val lang: String,
    val label: String? = null,
)

@Serializable
internal data class CatalogResponse(val metas: List<MetaPreview> = emptyList())

@Serializable
internal data class StreamsResponse(val streams: List<Stream> = emptyList())

@Serializable
internal data class SubtitlesResponse(val subtitles: List<Subtitle> = emptyList())
