package app.agregateur.core.playback

import app.agregateur.core.addon.UrlPolicy
import app.agregateur.core.model.Stream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl

enum class ArchiveKind(val path: String) { RAR("rar"), ZIP("zip"), TAR("tar") }

/** Ce que le relais doit préparer. Les champs sont déjà validés : le relais peut les utiliser tels quels. */
sealed interface RelayTarget {
    data class Torrent(val infoHash: String, val fileIdx: Int?, val trackers: List<String>) : RelayTarget

    data class Archive(
        val kind: ArchiveKind,
        val urls: List<String>,
        val fileIdx: Int?,
        val fileMustInclude: String?,
    ) : RelayTarget
}

private val infoHashHex = Regex("^[0-9a-fA-F]{40}$")
private const val MAX_TRACKERS = 30
private const val MAX_VOLUMES = 500
private const val MAX_PATTERN = 200

internal fun torrentTarget(stream: Stream): RelayTarget.Torrent? {
    val hash = stream.infoHash?.takeIf { infoHashHex.matches(it) } ?: return null
    val trackers = stream.sources.orEmpty()
        .mapNotNull { it.removePrefix("tracker:").takeIf { s -> s != it } }
        .filter { it.startsWith("udp://") || UrlPolicy.isWebUrl(it) }
        .distinct()
        .take(MAX_TRACKERS)
    return RelayTarget.Torrent(hash.lowercase(), stream.fileIdx?.takeIf { it >= 0 }, trackers)
}

/**
 * Archives que le relais sait lire sans tout télécharger : RAR non compressé (plusieurs volumes),
 * ZIP sans compression et TAR, un seul volume. 7z et tgz exigent une décompression complète : non pris en charge.
 */
internal fun archiveTarget(stream: Stream): RelayTarget.Archive? {
    val (kind, sources) = when {
        stream.rarUrls != null -> ArchiveKind.RAR to stream.rarUrls
        stream.zipUrls != null -> ArchiveKind.ZIP to stream.zipUrls
        stream.tarUrls != null -> ArchiveKind.TAR to stream.tarUrls
        else -> return null
    }
    val urls = sources.map { sourceUrl(it) ?: return null }
    if (urls.isEmpty() || urls.size > MAX_VOLUMES || urls.any { !UrlPolicy.isSecureOrLocal(it) }) return null
    if (kind != ArchiveKind.RAR && urls.size > 1) return null
    val pattern = stream.fileMustInclude?.takeIf { it.length <= MAX_PATTERN && fileMatcher(it) != null }
    return RelayTarget.Archive(kind, urls, stream.fileIdx?.takeIf { it >= 0 }, pattern)
}

private fun sourceUrl(element: JsonElement): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull
    is JsonObject -> (element["url"] as? JsonPrimitive)?.contentOrNull
    else -> null
}

/** `fileMustInclude` s'écrit à la façon JavaScript (`/\.mkv$|\.mp4$/i`) ou comme un motif nu. */
fun fileMatcher(pattern: String): Regex? = runCatching {
    val js = Regex("^/(.*)/([a-z]*)$").find(pattern)
    if (js == null) {
        Regex(pattern)
    } else {
        val options = if ('i' in js.groupValues[2]) setOf(RegexOption.IGNORE_CASE) else emptySet()
        Regex(js.groupValues[1], options)
    }
}.getOrNull()

/** Requêtes comprises par le relais. Le secret en tête de chemin empêche les autres applications de s'en servir. */
sealed interface RelayRequest {
    data class Play(val target: RelayTarget) : RelayRequest
    data class TorrentStatus(val infoHash: String) : RelayRequest
}

object RelayUrls {
    const val HOST = "127.0.0.1"

    fun play(port: Int, token: String, target: RelayTarget): String = base(port, token).apply {
        when (target) {
            is RelayTarget.Torrent -> {
                addPathSegment("torrent").addPathSegment(target.infoHash).addPathSegment(target.fileIdx?.toString() ?: "auto")
                target.trackers.forEach { addQueryParameter("tr", it) }
            }
            is RelayTarget.Archive -> {
                addPathSegment("archive").addPathSegment(target.kind.path).addPathSegment(target.fileIdx?.toString() ?: "auto")
                target.urls.forEach { addQueryParameter("u", it) }
                target.fileMustInclude?.let { addQueryParameter("m", it) }
            }
        }
    }.build().toString()

    fun torrentStatus(port: Int, token: String, infoHash: String): String =
        base(port, token).addPathSegment("status").addPathSegment(infoHash).build().toString()

    private fun base(port: Int, token: String) = HttpUrl.Builder().scheme("http").host(HOST).port(port).addPathSegment(token)

    /** Relit une adresse produite par [play] ou [torrentStatus] ; `null` si elle n'en vient pas ou si le secret diffère. */
    fun parse(url: HttpUrl, token: String): RelayRequest? {
        val segments = url.pathSegments
        if (segments.isEmpty() || !constantTimeEquals(segments[0], token)) return null
        val rest = segments.drop(1)
        return when {
            rest.size == 2 && rest[0] == "status" ->
                rest[1].takeIf { infoHashHex.matches(it) }?.let { RelayRequest.TorrentStatus(it.lowercase()) }
            rest.size == 3 && rest[0] == "torrent" -> {
                val stream = Stream(
                    infoHash = rest[1],
                    fileIdx = fileIndex(rest[2]) ?: return null,
                    sources = url.queryParameterValues("tr").filterNotNull().map { "tracker:$it" },
                )
                torrentTarget(stream)?.let { RelayRequest.Play(it) }
            }
            rest.size == 3 && rest[0] == "archive" -> {
                val kind = ArchiveKind.entries.firstOrNull { it.path == rest[1] } ?: return null
                val urls = url.queryParameterValues("u").filterNotNull().map(::JsonPrimitive)
                val stream = Stream(
                    fileIdx = fileIndex(rest[2]) ?: return null,
                    fileMustInclude = url.queryParameter("m"),
                    rarUrls = urls.takeIf { kind == ArchiveKind.RAR },
                    zipUrls = urls.takeIf { kind == ArchiveKind.ZIP },
                    tarUrls = urls.takeIf { kind == ArchiveKind.TAR },
                )
                archiveTarget(stream)?.let { RelayRequest.Play(it) }
            }
            else -> null
        }
    }

    /** `auto` → -1 (le relais choisit), un entier positif → lui-même, autre chose → refus. */
    private fun fileIndex(segment: String): Int? =
        if (segment == "auto") -1 else segment.toIntOrNull()?.takeIf { it >= 0 }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
