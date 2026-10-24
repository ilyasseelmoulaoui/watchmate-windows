package app.agregateur.core.addon

import app.agregateur.core.model.CatalogResponse
import app.agregateur.core.model.Manifest
import app.agregateur.core.model.Meta
import app.agregateur.core.model.MetaPreview
import app.agregateur.core.model.Stream
import app.agregateur.core.model.StreamsResponse
import app.agregateur.core.model.Subtitle
import app.agregateur.core.model.SubtitlesResponse
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

sealed interface AddonError {
    data object InvalidUrl : AddonError
    data object InsecureNotAllowed : AddonError
    data class Unreachable(val reason: String) : AddonError
    data class HttpStatus(val code: Int) : AddonError
    data class InvalidResponse(val reason: String) : AddonError
}

sealed interface AddonResult<out T> {
    data class Success<T>(val value: T) : AddonResult<T>
    data class Failure(val error: AddonError) : AddonResult<Nothing>
}

val AddonJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Client du protocole public de modules (HTTP + JSON). Règles : délai maximal de 30 s (R9) ;
 * trafic non chiffré refusé, sauf vers l'appareil lui-même ou vers un hôte accepté explicitement.
 */
class AddonClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val insecureHosts: Set<String> = emptySet(),
    private val json: Json = AddonJson,
) {
    suspend fun manifest(addonUrl: String): AddonResult<Manifest> {
        val base = AddonUrls.base(addonUrl) ?: return failure(AddonError.InvalidUrl)
        return get(AddonUrls.manifest(base)) { json.decodeFromString<Manifest>(it) }
    }

    suspend fun catalog(
        addonUrl: String,
        type: String,
        catalogId: String,
        extra: Map<String, String> = emptyMap(),
    ): AddonResult<List<MetaPreview>> = resource(addonUrl, "catalog", type, catalogId, extra) {
        json.decodeFromString<CatalogResponse>(it).metas
    }

    /** Succès avec `null` si le module répond sans fiche (objet vide). */
    suspend fun meta(addonUrl: String, type: String, id: String): AddonResult<Meta?> =
        resource(addonUrl, "meta", type, id) { body ->
            val meta = (json.parseToJsonElement(body) as? JsonObject)?.get("meta") as? JsonObject
            if (meta == null || "id" !in meta) null else json.decodeFromJsonElement<Meta>(meta)
        }

    suspend fun streams(addonUrl: String, type: String, videoId: String): AddonResult<List<Stream>> =
        resource(addonUrl, "stream", type, videoId) {
            json.decodeFromString<StreamsResponse>(it).streams
        }

    suspend fun subtitles(
        addonUrl: String,
        type: String,
        videoId: String,
        extra: Map<String, String> = emptyMap(),
    ): AddonResult<List<Subtitle>> = resource(addonUrl, "subtitles", type, videoId, extra) {
        json.decodeFromString<SubtitlesResponse>(it).subtitles
    }

    private suspend fun <T> resource(
        addonUrl: String,
        resource: String,
        type: String,
        id: String,
        extra: Map<String, String> = emptyMap(),
        parse: (String) -> T,
    ): AddonResult<T> {
        val base = AddonUrls.base(addonUrl) ?: return failure(AddonError.InvalidUrl)
        return get(AddonUrls.resource(base, resource, type, id, extra), parse)
    }

    private suspend fun <T> get(url: HttpUrl, parse: (String) -> T): AddonResult<T> {
        if (!url.isHttps && !isInsecureAllowed(url.host)) return failure(AddonError.InsecureNotAllowed)
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        return try {
            http.newCall(request).await().use { response ->
                if (!response.isSuccessful) return failure(AddonError.HttpStatus(response.code))
                val body = withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
                AddonResult.Success(parse(body))
            }
        } catch (e: IOException) {
            failure(AddonError.Unreachable(e.message ?: e::class.simpleName.orEmpty()))
        } catch (e: SerializationException) {
            failure(AddonError.InvalidResponse(e.message.orEmpty()))
        } catch (e: IllegalArgumentException) {
            failure(AddonError.InvalidResponse(e.message.orEmpty()))
        }
    }

    private fun isInsecureAllowed(host: String): Boolean =
        host == "127.0.0.1" || host == "localhost" || host == "::1" || host in insecureHosts

    private fun failure(error: AddonError) = AddonResult.Failure(error)

    companion object {
        fun defaultHttpClient(cacheDir: File? = null): OkHttpClient =
            OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .proxySelector(LocalProxySelector)
                .apply { if (cacheDir != null) cache(Cache(cacheDir, 20L * 1024 * 1024)) }
                .build()
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
        },
    )
}
