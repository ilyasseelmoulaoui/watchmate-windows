package app.agregateur.core.sync

import app.agregateur.core.storage.JsonFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable

interface SessionStore {
    suspend fun load(): Session?

    @Throws(IOException::class)
    suspend fun save(session: Session?)
}

class InMemorySessionStore(private var session: Session? = null) : SessionStore {
    override suspend fun load() = session
    override suspend fun save(session: Session?) {
        this.session = session
    }
}

/** Session dans un fichier privé de l'application (le jeton de renouvellement permet de rester connecté). */
class FileSessionStore(file: File) : SessionStore {
    private val json = JsonFile(file, Session.serializer().nullable) { null }
    override suspend fun load(): Session? = json.load()
    override suspend fun save(session: Session?) = json.save(session)
}

/** Un lien d'e-mail attendu : demandé depuis cet appareil, pour cette adresse, valable un temps limité. */
@Serializable
data class PendingAuth(val kind: String, val email: String, val untilSeconds: Long)

interface PendingStore {
    suspend fun load(): PendingAuth?
    suspend fun save(pending: PendingAuth?)
}

class InMemoryPendingStore(private var pending: PendingAuth? = null) : PendingStore {
    override suspend fun load() = pending
    override suspend fun save(pending: PendingAuth?) {
        this.pending = pending
    }
}

class FilePendingStore(file: File) : PendingStore {
    private val json = JsonFile(file, PendingAuth.serializer().nullable) { null }
    override suspend fun load(): PendingAuth? = runCatching { json.load() }.getOrNull()
    override suspend fun save(pending: PendingAuth?) = runCatching { json.save(pending) }.let { }
}

sealed interface LinkResult {
    /** Adresse confirmée : l'utilisateur est connecté. */
    data object SignedIn : LinkResult

    /** Lien de réinitialisation valide : l'utilisateur est connecté et doit choisir un nouveau mot de passe. */
    data object ChoosePassword : LinkResult

    /** Lien périmé ou déjà utilisé. */
    data object Expired : LinkResult

    /** Lien qui ne correspond à aucune demande de cet appareil : ignoré. */
    data object Ignored : LinkResult
}

sealed interface AccountResult {
    data object Ok : AccountResult
    /** Compte créé ; il faut ouvrir le lien reçu par e-mail avant de se connecter. */
    data object ConfirmationSent : AccountResult
    data class Failed(val error: SupabaseError) : AccountResult
}

/**
 * Compte de l'utilisateur. Le jeton d'accès est renouvelé avant son expiration ; si le renouvellement est refusé
 * (compte supprimé, session révoquée), l'utilisateur est déconnecté proprement.
 */
class AccountService(
    private val client: SupabaseClient,
    private val store: SessionStore,
    private val pending: PendingStore = InMemoryPendingStore(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val state = MutableStateFlow<Session?>(null)
    private val lock = Mutex()

    val session: StateFlow<Session?> = state.asStateFlow()

    suspend fun load() {
        state.value = runCatching { store.load() }.getOrNull()
    }

    suspend fun signIn(email: String, password: String): AccountResult = attempt {
        open(client.signIn(email, password))
        AccountResult.Ok
    }

    suspend fun signUp(email: String, password: String): AccountResult = attempt {
        val session = client.signUp(email, password, AUTH_REDIRECT)
        if (session == null) {
            expect("signup", email)
            AccountResult.ConfirmationSent
        } else {
            open(session)
            AccountResult.Ok
        }
    }

    suspend fun resendConfirmation(email: String): AccountResult = attempt {
        client.resendConfirmation(email, AUTH_REDIRECT)
        expect("signup", email)
        AccountResult.Ok
    }

    /** Envoie l'e-mail de réinitialisation. Le serveur répond de la même façon que l'adresse existe ou non. */
    suspend fun requestPasswordReset(email: String): AccountResult = attempt {
        client.recover(email, AUTH_REDIRECT)
        expect("recovery", email)
        AccountResult.Ok
    }

    suspend fun changePassword(newPassword: String): AccountResult = attempt {
        authorized { token -> client.updatePassword(token, newPassword) }
        AccountResult.Ok
    }

    /**
     * Lien `watchmate://auth#…` ouvert depuis un e-mail. Il n'est pris en compte que s'il répond à une demande récente
     * de cet appareil, pour la même adresse : n'importe quelle application peut ouvrir ce lien avec les jetons d'un
     * compte qu'elle contrôle, et la bibliothèque de l'utilisateur partirait dans ce compte.
     */
    suspend fun handleLink(link: AuthLink): LinkResult {
        val waiting = pending.load()?.takeIf { it.untilSeconds > nowSeconds() } ?: return LinkResult.Ignored
        if (link is AuthLink.Error) {
            pending.save(null)
            return LinkResult.Expired
        }
        link as AuthLink.Tokens
        if (link.type != waiting.kind) return LinkResult.Ignored
        val owner = try {
            client.user(link.accessToken)
        } catch (e: SupabaseException) {
            return LinkResult.Ignored
        }
        if (!owner.email.equals(waiting.email.trim(), ignoreCase = true)) return LinkResult.Ignored
        pending.save(null)
        open(Session(link.accessToken, link.refreshToken, link.expiresAt, owner))
        return if (waiting.kind == "recovery") LinkResult.ChoosePassword else LinkResult.SignedIn
    }

    private suspend fun expect(kind: String, email: String) =
        pending.save(PendingAuth(kind, email.trim(), nowSeconds() + LINK_VALIDITY_S))

    /** La déconnexion locale ne dépend pas du réseau ; la révocation côté serveur est tentée au passage. */
    suspend fun signOut() {
        val current = state.value ?: return
        runCatching { client.signOut(current.accessToken) }
        close()
    }

    suspend fun deleteAccount(): AccountResult = attempt {
        authorized { token -> client.deleteAccount(token) }
        close()
        AccountResult.Ok
    }

    /**
     * Exécute [block] avec un jeton valide : renouvelé s'il expire dans la minute, et une fois de plus si le serveur
     * le refuse. Sans session, ou si le renouvellement échoue pour de bon, lève UNAUTHORIZED.
     */
    suspend fun <T> authorized(block: suspend (String) -> T): T {
        val token = freshToken(force = false)
        return try {
            block(token)
        } catch (e: SupabaseException) {
            if (e.error != SupabaseError.UNAUTHORIZED) throw e
            block(freshToken(force = true))
        }
    }

    private suspend fun freshToken(force: Boolean): String = lock.withLock {
        val current = state.value ?: throw SupabaseException(SupabaseError.UNAUTHORIZED, message = "Non connecté")
        if (!force && current.expiresAt - nowSeconds() > REFRESH_MARGIN_S) return current.accessToken
        val renewed = try {
            client.refresh(current.refreshToken)
        } catch (e: SupabaseException) {
            if (e.error != SupabaseError.NETWORK && e.error != SupabaseError.RATE_LIMITED) closeLocked()
            throw e
        }
        save(renewed)
        renewed.accessToken
    }

    private suspend fun open(session: Session) = lock.withLock { save(session) }

    private suspend fun close() = lock.withLock { closeLocked() }

    private suspend fun closeLocked() {
        runCatching { store.save(null) }
        state.value = null
    }

    private suspend fun save(session: Session) {
        store.save(session)
        state.value = session
    }

    private suspend fun attempt(block: suspend () -> AccountResult): AccountResult = try {
        block()
    } catch (e: SupabaseException) {
        AccountResult.Failed(e.error)
    } catch (e: IOException) {
        AccountResult.Failed(SupabaseError.SERVER)
    }

    companion object {
        /** Adresse ouverte par les liens des e-mails ; à déclarer dans Supabase (Authentication → URL Configuration). */
        const val AUTH_REDIRECT = "watchmate://auth"
        private const val REFRESH_MARGIN_S = 60L
        private const val LINK_VALIDITY_S = 3600L
    }
}
