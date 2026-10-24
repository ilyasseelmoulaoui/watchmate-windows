package app.agregateur.core

import app.agregateur.core.sync.AccountResult
import app.agregateur.core.sync.AccountService
import app.agregateur.core.sync.AuthUser
import app.agregateur.core.sync.InMemorySessionStore
import app.agregateur.core.sync.RemoteItem
import app.agregateur.core.sync.Session
import app.agregateur.core.sync.SupabaseClient
import app.agregateur.core.sync.SupabaseConfig
import app.agregateur.core.sync.SupabaseError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class SupabaseClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = SupabaseClient(SupabaseConfig(server.url("/").toString(), "ANON"), OkHttpClient())

    @AfterTest
    fun stop() = server.shutdown()

    private fun session(token: String, expiresAt: Long) =
        """{"access_token":"$token","refresh_token":"r-$token","expires_in":3600,"expires_at":$expiresAt,"token_type":"bearer","user":{"id":"u1","email":"a@b.c","aud":"authenticated"}}"""

    @Test
    fun `connexion, cle publique et identifiants envoyes, session lue`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(session("t1", 2_000_000_000)))
        val s = client.signIn(" a@b.c ", "secret")
        assertEquals("t1", s.accessToken)
        assertEquals("u1", s.user.id)
        val req = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=password", req.path)
        assertEquals("ANON", req.getHeader("apikey"))
        assertEquals("""{"email":"a@b.c","password":"secret"}""", req.body.readUtf8())
    }

    @Test
    fun `erreurs de connexion reconnues`() = runBlocking<Unit> {
        val service = AccountService(client, InMemorySessionStore())
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}"""))
        assertEquals(AccountResult.Failed(SupabaseError.INVALID_CREDENTIALS), service.signIn("a@b.c", "x"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"Email not confirmed"}"""))
        assertEquals(AccountResult.Failed(SupabaseError.EMAIL_NOT_CONFIRMED), service.signIn("a@b.c", "x"))
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"code":422,"error_code":"weak_password","msg":"Password should be at least 6 characters."}"""))
        assertEquals(AccountResult.Failed(SupabaseError.WEAK_PASSWORD), service.signUp("a@b.c", "x"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"code":429,"error_code":"over_email_send_rate_limit","msg":"..."}"""))
        assertEquals(AccountResult.Failed(SupabaseError.RATE_LIMITED), service.signUp("a@b.c", "secret1"))
        assertNull(service.session.value)
    }

    @Test
    fun `inscription avec confirmation par e-mail`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"id":"u1","email":"a@b.c","confirmation_sent_at":"2026-10-19T00:00:00Z"}"""))
        val service = AccountService(client, InMemorySessionStore())
        assertEquals(AccountResult.ConfirmationSent, service.signUp("a@b.c", "secret1"))
        assertNull(service.session.value)
    }

    @Test
    fun `jeton renouvele avant expiration puis apres un refus`() = runBlocking<Unit> {
        val now = 1_000_000L
        val store = InMemorySessionStore(Session("old", "r-old", expiresAt = now + 10, user = AuthUser("u1")))
        val service = AccountService(client, store) { now }
        service.load()
        server.enqueue(MockResponse().setBody(session("t2", now + 3600)))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"PGRST301","message":"JWT expired"}"""))
        server.enqueue(MockResponse().setBody(session("t3", now + 3600)))
        server.enqueue(MockResponse().setBody("[]"))
        val items = service.authorized { token -> client.pull(token, 0) }
        assertTrue(items.isEmpty())
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        assertEquals("Bearer t2", server.takeRequest().getHeader("Authorization"))
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        val pull = server.takeRequest()
        assertEquals("Bearer t3", pull.getHeader("Authorization"))
        assertEquals("/rest/v1/sync_items?select=kind,key,data,updated_at,seq&seq=gt.0&order=seq.asc&limit=500", pull.path)
        assertEquals("t3", store.load()?.accessToken)
    }

    @Test
    fun `renouvellement refuse, deconnexion propre`() = runBlocking<Unit> {
        val store = InMemorySessionStore(Session("old", "r-old", expiresAt = 0, user = AuthUser("u1")))
        val service = AccountService(client, store) { 1_000 }
        service.load()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}"""))
        val error = runCatching { service.authorized { "jamais" } }.exceptionOrNull()
        assertTrue(error != null)
        assertNull(service.session.value)
        assertNull(store.load())
    }

    @Test
    fun `envoi, suppression transmise sans donnees`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("42"))
        client.push(
            "tok",
            listOf(
                RemoteItem("library", "movie:tt1", buildJsonObject { put("id", "tt1") }, 5),
                RemoteItem("module", "https://m/manifest.json", null, 6),
            ),
        )
        val req = server.takeRequest()
        assertEquals("/rest/v1/rpc/sync_push", req.path)
        assertEquals(
            """{"items":[{"kind":"library","key":"movie:tt1","data":{"id":"tt1"},"updated_at":5},{"kind":"module","key":"https://m/manifest.json","updated_at":6}]}""",
            req.body.readUtf8(),
        )
    }
}
