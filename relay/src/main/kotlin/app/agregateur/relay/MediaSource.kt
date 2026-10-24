package app.agregateur.relay

import java.io.IOException
import java.io.InputStream

/** Un fichier vidéo que le relais sait lire à partir de n'importe quelle position. */
interface MediaSource {
    val name: String
    val size: Long

    /** Flux à partir de [position] ; il peut bloquer tant que les données ne sont pas arrivées. */
    fun open(position: Long): InputStream
}

/** Erreur que le relais renvoie au lecteur avec un statut HTTP précis. */
class RelayException(val status: Int, message: String) : IOException(message) {
    companion object {
        fun unsupported(message: String) = RelayException(415, message)
        fun notFound(message: String) = RelayException(404, message)
        fun upstream(message: String) = RelayException(502, message)
        fun timeout(message: String) = RelayException(504, message)
    }
}

private val videoTypes = mapOf(
    "mp4" to "video/mp4",
    "m4v" to "video/mp4",
    "mkv" to "video/x-matroska",
    "webm" to "video/webm",
    "avi" to "video/x-msvideo",
    "ts" to "video/mp2t",
    "m2ts" to "video/mp2t",
    "mov" to "video/quicktime",
    "wmv" to "video/x-ms-wmv",
    "flv" to "video/x-flv",
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "flac" to "audio/flac",
)

fun contentTypeOf(name: String): String =
    videoTypes[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
