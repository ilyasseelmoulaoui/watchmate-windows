package app.agregateur.core

import app.agregateur.core.sync.AccountResult
import app.agregateur.core.sync.AccountService
import app.agregateur.core.sync.AuthLink
import app.agregateur.core.sync.InMemoryPendingStore
import app.agregateur.core.sync.InMemorySessionStore
import app.agregateur.core.sync.LinkResult
import app.agregateur.core.sync.SupabaseClient
import app.agregateur.core.sync.SupabaseConfig
import app.agregateur.core.sync.parseAuthFragment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class AuthLinkTest {
    private val server = MockWebServer().apply { start() }
    private val client = SupabaseClient(SupabaseConfig(server.url("/").toString(), "ANON"), OkHttpClient())
    private var now = 1_000_000L
    private val sessions = InMemorySessionStore()
    private val service = AccountService(client, sessions, InMemoryPendingStore()) { now }

    @AfterTest
    fun stop() = server.shutdown()

    private val recoveryFragment = "access_token=AT&expires_at=${1_000_000 + 3600}&expires_in=3600&refresh_token=RT&token_type=bearer&type=recovery"

    private fun user(email: String) = MockResponse().setBody("""{"id":"u1","email":"$email","aud":"authenticated"}""")

    @Test
    fun `fragment lu, erreur lue, reste ignore`() {
        val tokens = assertIs<AuthLink.Tokens>(parseAuthFragment(recoveryFragment, 1))
        assertEquals(AuthLink.Tokens("recovery", "AT", "RT", 1_003_600), tokens)
        val error = assertIs<AuthLink.Error>(
            parseAuthFragment("error=access_denied&error_code=otp_expired&error_description=Email+link+is+invalid+or+has+expired"),
        )
        assertEquals("otp_expired", error.code)
        assertEquals("Email link is invalid or has expired", error.description)
        assertNull(parseAuthFragment(null))
        assertNull(parseAuthFragment(""))
        assertNull(parseAuthFragment("access_token=seul"))
    }

    @Test
    fun `demande de reinitialisation, adresse de retour transmise`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("{}"))
        assertEquals(AccountResult.Ok, service.requestPasswordReset(" a@b.c "))
        val req = server.takeRequest()
        assertEquals("/auth/v1/recover?redirect_to=watchmate%3A%2F%2Fauth", req.path)
        assertEquals("""{"email":"a@b.c"}""", req.body.readUtf8())
    }

    @Test
    fun `lien de reinitialisation attendu, session ouverte puis nouveau mot de passe`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("{}"))
        service.requestPasswordReset("a@b.c")
        server.takeRequest()

        server.enqueue(user("A@B.C"))
        val link = assertIs<AuthLink.Tokens>(parseAuthFragment(recoveryFragment, now))
        assertEquals(LinkResult.ChoosePassword, service.handleLink(link))
        assertEquals("Bearer AT", server.takeRequest().getHeader("Authorization"))
        assertEquals("u1", service.session.value?.user?.id)

        server.enqueue(MockResponse().setBody("{}"))
        assertEquals(AccountResult.Ok, service.changePassword("nouveau-mdp"))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/auth/v1/user", put.path)
        assertEquals("""{"password":"nouveau-mdp"}""", put.body.readUtf8())

        assertEquals(LinkResult.Ignored, service.handleLink(link), "un lien ne sert qu'une fois")
    }

    @Test
    fun `lien sans demande, d une autre adresse, d un autre type ou perime, ignore`() = runBlocking<Unit> {
        val link = assertIs<AuthLink.Tokens>(parseAuthFragment(recoveryFragment, now))
        assertEquals(LinkResult.Ignored, service.handleLink(link), "aucune demande de cet appareil")
        assertEquals(0, server.requestCount)

        server.enqueue(MockResponse().setBody("{}")); service.requestPasswordReset("a@b.c")
        server.enqueue(user("autre@b.c"))
        assertEquals(LinkResult.Ignored, service.handleLink(link), "jetons d'un autre compte")
        assertNull(service.session.value)

        assertEquals(LinkResult.Ignored, service.handleLink(link.copy(type = "signup")), "mauvais type")

        now += 3601
        assertEquals(LinkResult.Ignored, service.handleLink(link), "demande trop ancienne")
        assertNull(sessions.load())
    }

    @Test
    fun `confirmation d adresse connecte directement, lien perime signale`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"id":"u1","email":"a@b.c"}"""))
        service.signUp("a@b.c", "secret1")
        assertEquals("/auth/v1/signup?redirect_to=watchmate%3A%2F%2Fauth", server.takeRequest().path)

        server.enqueue(user("a@b.c"))
        val link = assertIs<AuthLink.Tokens>(parseAuthFragment(recoveryFragment.replace("recovery", "signup"), now))
        assertEquals(LinkResult.SignedIn, service.handleLink(link))
        assertEquals("a@b.c", service.session.value?.user?.email)

        server.enqueue(MockResponse().setBody("{}")); service.requestPasswordReset("a@b.c")
        assertEquals(LinkResult.Expired, service.handleLink(AuthLink.Error("otp_expired", null)))
    }
}
