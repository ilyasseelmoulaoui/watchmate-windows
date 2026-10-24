package app.agregateur.core.sync

import app.agregateur.core.addon.await
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Projet Supabase : adresse et clé publique (« anon »), qui peut figurer dans l'application. */
data class SupabaseConfig(val url: String, val anonKey: String)

@Serializable
data class AuthUser(val id: String, val email: String? = null)

/** `expiresAt` : secondes depuis 1970 (format de Supabase). */
@Serializable
data class Session(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long = 0,
    val user: AuthUser,
)

/** Un élément synchronisé, tel que le serveur le stocke. `data` nul : élément supprimé. */
@Serializable
data class RemoteItem(
    val kind: String,
    val key: String,
    val data: JsonElement? = null,
    @SerialName("updated_at") val updatedAt: Long,
    val seq: Long = 0,
)

/** `status` : `ok`, `not_found` (code inconnu ou salle de plus de 24 h) ou `full`. */
@Serializable
data class PartyJoinResult(val status: String, val host: String? = null)

/** `status` : `ok`, `not_found` (pas de compte), `not_member`, `self` ou `invalid`. */
@Serializable
data class PartyInviteSendResult(val status: String)

/** Invitation reçue (in-app). `from` : nom affiché de l'expéditeur (partie avant @). */
@Serializable
data class PartyInvite(
    val id: String,
    val code: String,
    val from: String,
    @SerialName("created_at") val createdAt: Long = 0,
)

enum class SupabaseError {
    INVALID_CREDENTIALS,
    EMAIL_NOT_CONFIRMED,
    USER_EXISTS,
    WEAK_PASSWORD,
    INVALID_EMAIL,
    RATE_LIMITED,
    UNAUTHORIZED,
    NETWORK,
    SERVER,
}

class SupabaseException(val error: SupabaseError, val status: Int = 0, message: String = "") : IOException(message)

/** Appels REST à Supabase (authentification par e-mail, table `sync_items` et fonctions de `supabase/schema.sql`). */
class SupabaseClient(private val config: SupabaseConfig, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val base: HttpUrl = config.url.trimEnd('/').toHttpUrl()

    /** Renvoie la session si le compte est utilisable tout de suite, `null` s'il doit d'abord être confirmé par e-mail. */
    suspend fun signUp(email: String, password: String, redirectTo: String? = null): Session? {
        val url = auth("signup").newBuilder().apply { redirectTo?.let { addQueryParameter("redirect_to", it) } }.build()
        val body = call(post(url, credentials(email, password)))
        val obj = json.parseToJsonElement(body) as? JsonObject
        return if (obj?.get("access_token") != null) json.decodeFromString(Session.serializer(), body) else null
    }

    suspend fun signIn(email: String, password: String): Session =
        json.decodeFromString(Session.serializer(), call(post(token("password"), credentials(email, password))))

    suspend fun refresh(refreshToken: String): Session = json.decodeFromString(
        Session.serializer(),
        call(post(token("refresh_token"), buildJsonObject { put("refresh_token", refreshToken) })),
    )

    suspend fun resendConfirmation(email: String, redirectTo: String? = null) {
        val url = auth("resend").newBuilder().apply { redirectTo?.let { addQueryParameter("redirect_to", it) } }.build()
        call(post(url, buildJsonObject { put("type", "signup"); put("email", email.trim()) }))
    }

    /** Envoie un e-mail de réinitialisation ; son lien ouvre [redirectTo] avec les jetons dans le fragment. */
    suspend fun recover(email: String, redirectTo: String) {
        val url = auth("recover").newBuilder().addQueryParameter("redirect_to", redirectTo).build()
        call(post(url, buildJsonObject { put("email", email.trim()) }))
    }

    /** Titulaire d'un jeton d'accès : sert à vérifier un jeton reçu par lien avant de s'en servir. */
    suspend fun user(accessToken: String): AuthUser =
        json.decodeFromString(AuthUser.serializer(), call(request(auth("user"), accessToken).get().build()))

    suspend fun updatePassword(accessToken: String, newPassword: String) {
        val body = buildJsonObject { put("password", newPassword) }.toString().toRequestBody(JSON)
        call(request(auth("user"), accessToken).put(body).build())
    }

    suspend fun signOut(accessToken: String) {
        call(post(auth("logout"), JsonObject(emptyMap()), accessToken))
    }

    /** Éléments écrits après [afterSeq], dans l'ordre du serveur. */
    suspend fun pull(accessToken: String, afterSeq: Long, limit: Int = 500): List<RemoteItem> {
        val url = base.newBuilder().addPathSegments("rest/v1/sync_items")
            .addEncodedQueryParameter("select", "kind,key,data,updated_at,seq")
            .addQueryParameter("seq", "gt.$afterSeq")
            .addQueryParameter("order", "seq.asc")
            .addQueryParameter("limit", limit.toString())
            .build()
        val body = call(request(url, accessToken).get().build())
        return json.decodeFromString(ListSerializer(RemoteItem.serializer()), body)
    }

    suspend fun push(accessToken: String, items: List<RemoteItem>) {
        val payload = buildJsonObject {
            put("items", json.encodeToJsonElement(ListSerializer(PushItem.serializer()), items.map { PushItem(it.kind, it.key, it.data, it.updatedAt) }))
        }
        call(post(rpc("sync_push"), payload, accessToken))
    }

    suspend fun deleteAccount(accessToken: String) {
        call(post(rpc("delete_my_account"), JsonObject(emptyMap()), accessToken))
    }

    /** Crée une salle (fonctions de `supabase/watch_party.sql`) ; le créateur en est l'hôte et le premier membre. */
    suspend fun partyCreate(accessToken: String): String =
        json.decodeFromString(String.serializer(), call(post(rpc("party_create"), JsonObject(emptyMap()), accessToken)))

    suspend fun partyJoin(accessToken: String, code: String): PartyJoinResult = json.decodeFromString(
        PartyJoinResult.serializer(),
        call(post(rpc("party_join"), buildJsonObject { put("p_code", code) }, accessToken)),
    )

    suspend fun partyLeave(accessToken: String, code: String) {
        call(post(rpc("party_leave"), buildJsonObject { put("p_code", code) }, accessToken))
    }

    suspend fun partyInvite(accessToken: String, code: String, email: String): PartyInviteSendResult =
        json.decodeFromString(
            PartyInviteSendResult.serializer(),
            call(
                post(
                    rpc("party_invite"),
                    buildJsonObject {
                        put("p_code", code)
                        put("p_email", email)
                    },
                    accessToken,
                ),
            ),
        )

    suspend fun partyPendingInvites(accessToken: String): List<PartyInvite> =
        json.decodeFromString(
            ListSerializer(PartyInvite.serializer()),
            call(post(rpc("party_pending_invites"), JsonObject(emptyMap()), accessToken)),
        )

    suspend fun partyDismissInvite(accessToken: String, inviteId: String) {
        call(post(rpc("party_dismiss_invite"), buildJsonObject { put("p_id", inviteId) }, accessToken))
    }

    @Serializable
    private data class PushItem(
        val kind: String,
        val key: String,
        val data: JsonElement?,
        @SerialName("updated_at") val updatedAt: Long,
    )

    private fun auth(path: String) = base.newBuilder().addPathSegments("auth/v1/$path").build()

    private fun token(grant: String) = base.newBuilder().addPathSegments("auth/v1/token").addQueryParameter("grant_type", grant).build()

    private fun rpc(name: String) = base.newBuilder().addPathSegments("rest/v1/rpc/$name").build()

    private fun credentials(email: String, password: String) = buildJsonObject {
        put("email", email.trim())
        put("password", password)
    }

    private fun request(url: HttpUrl, accessToken: String?) = Request.Builder().url(url)
        .header("apikey", config.anonKey)
        .header("Authorization", "Bearer ${accessToken ?: config.anonKey}")
        .header("Accept", "application/json")

    private fun post(url: HttpUrl, body: JsonElement, accessToken: String? = null) =
        request(url, accessToken).post(body.toString().toRequestBody(JSON)).build()

    private suspend fun call(request: Request): String {
        val (code, body) = try {
            http.newCall(request).await().use { response ->
                response.code to withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
            }
        } catch (e: IOException) {
            throw SupabaseException(SupabaseError.NETWORK, message = e.message.orEmpty())
        }
        if (code in 200..299) return body
        throw errorOf(code, body)
    }

    /** Erreurs de GoTrue (`error_code`, `msg`, ou `error`/`error_description` selon la version) et de PostgREST. */
    private fun errorOf(code: Int, body: String): SupabaseException {
        val obj = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        fun field(name: String) = runCatching { obj?.get(name)?.jsonPrimitive?.contentOrNull }.getOrNull()
        val errorCode = field("error_code") ?: field("error") ?: field("code")
        val message = field("msg") ?: field("error_description") ?: field("message") ?: body.take(200)
        val text = "$errorCode $message".lowercase()
        val kind = when {
            code == 429 || "rate_limit" in text -> SupabaseError.RATE_LIMITED
            "email_not_confirmed" in text || "email not confirmed" in text -> SupabaseError.EMAIL_NOT_CONFIRMED
            "invalid_credentials" in text || "invalid login credentials" in text -> SupabaseError.INVALID_CREDENTIALS
            "user_already_exists" in text || "already registered" in text -> SupabaseError.USER_EXISTS
            "weak_password" in text || "password should be" in text -> SupabaseError.WEAK_PASSWORD
            "email_address_invalid" in text || "invalid format" in text || "validate email" in text -> SupabaseError.INVALID_EMAIL
            code == 401 || "jwt expired" in text || "refresh_token_not_found" in text || "invalid refresh token" in text ->
                SupabaseError.UNAUTHORIZED
            else -> SupabaseError.SERVER
        }
        return SupabaseException(kind, code, message)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
