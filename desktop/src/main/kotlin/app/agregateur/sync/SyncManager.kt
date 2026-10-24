package app.agregateur.sync

import app.agregateur.core.library.LibraryRepository
import app.agregateur.core.modules.ModuleRepository
import app.agregateur.core.settings.SettingsRepository
import app.agregateur.core.sync.AccountResult
import app.agregateur.core.sync.AccountService
import app.agregateur.core.sync.LinkResult
import app.agregateur.core.sync.RemoteItem
import app.agregateur.core.sync.parseAuthFragment
import app.agregateur.core.sync.SupabaseClient
import app.agregateur.core.sync.SupabaseError
import app.agregateur.core.sync.SupabaseException
import app.agregateur.core.sync.SyncBackend
import app.agregateur.core.sync.SyncEngine
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncStatus {
    data object SignedOut : SyncStatus
    data object Running : SyncStatus
    data class Idle(val lastSyncAt: Long?) : SyncStatus
    data class Failed(val error: SupabaseError) : SyncStatus
}

/**
 * Quand synchroniser : à l'ouverture, au retour dans l'application, après la connexion, et quelques secondes après
 * chaque changement local (regroupés). Une synchronisation en cours n'est jamais interrompue.
 */
class SyncManager(
    private val account: AccountService,
    private val engine: SyncEngine,
    private val client: SupabaseClient,
    private val library: LibraryRepository,
    private val modules: ModuleRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.SignedOut)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val running = Mutex()
    private var scheduled: Job? = null

    private val backend = object : SyncBackend {
        override suspend fun pull(afterSeq: Long): List<RemoteItem> = account.authorized { client.pull(it, afterSeq) }
        override suspend fun push(items: List<RemoteItem>) = account.authorized { client.push(it, items) }
    }

    /** À appeler une fois les dépôts chargés : avant, une liste vide passerait pour une suppression. */
    suspend fun start() {
        engine.load()
        account.load()
        scope.launch {
            combine(library.entries, modules.modules, settings.settings) { _, _, _ -> }.collect {
                engine.noteLocalChanges()
                if (engine.pendingChanges > 0) schedule(CHANGE_DELAY_MS)
            }
        }
        scope.launch {
            account.session.map { it?.user?.id }.distinctUntilChanged().collect { userId ->
                if (userId == null) _status.value = SyncStatus.SignedOut else schedule(0)
            }
        }
    }

    fun syncNow() = schedule(0)

    /** Retour dans l'application : on récupère ce qui a changé ailleurs, sans insister si c'est tout récent. */
    fun onForeground() {
        val last = engine.lastSyncAt ?: 0
        if (System.currentTimeMillis() - last > FOREGROUND_MIN_GAP_MS) schedule(0)
    }

    suspend fun signIn(email: String, password: String): AccountResult = account.signIn(email, password)

    suspend fun signUp(email: String, password: String): AccountResult = account.signUp(email, password)

    suspend fun resendConfirmation(email: String): AccountResult = account.resendConfirmation(email)

    suspend fun requestPasswordReset(email: String): AccountResult = account.requestPasswordReset(email)

    suspend fun changePassword(newPassword: String): AccountResult =
        account.changePassword(newPassword).also { if (it == AccountResult.Ok) _choosePassword.value = false }

    /** Vrai quand un lien de réinitialisation vient d'être ouvert : l'écran demande alors le nouveau mot de passe. */
    private val _choosePassword = MutableStateFlow(false)
    val choosePassword: StateFlow<Boolean> = _choosePassword.asStateFlow()

    fun dismissChoosePassword() {
        _choosePassword.value = false
    }

    /** Lien `watchmate://auth#…` ouvert depuis un e-mail ; null si ce n'est pas un lien d'authentification. */
    suspend fun handleAuthLink(fragment: String?): LinkResult? {
        val link = parseAuthFragment(fragment) ?: return null
        return account.handleLink(link).also { if (it == LinkResult.ChoosePassword) _choosePassword.value = true }
    }

    /** Les données restent sur l'appareil ; seul le lien avec le compte est oublié. */
    suspend fun signOut() {
        scheduled?.cancel()
        running.withLock {
            account.signOut()
            engine.reset()
        }
    }

    suspend fun deleteAccount(): AccountResult {
        scheduled?.cancel()
        return running.withLock {
            account.deleteAccount().also { if (it == AccountResult.Ok) engine.reset() }
        }
    }

    private fun schedule(delayMs: Long) {
        if (account.session.value == null) return
        scheduled?.cancel()
        scheduled = scope.launch {
            delay(delayMs)
            scope.launch { running.withLock { runOnce() } }
        }
    }

    private suspend fun runOnce() {
        val session = account.session.value ?: return
        _status.value = SyncStatus.Running
        _status.value = try {
            engine.sync(session.user.id, backend)
            SyncStatus.Idle(engine.lastSyncAt)
        } catch (e: SupabaseException) {
            if (account.session.value == null) SyncStatus.SignedOut else SyncStatus.Failed(e.error)
        } catch (e: IOException) {
            SyncStatus.Failed(SupabaseError.SERVER)
        }
    }

    private companion object {
        const val CHANGE_DELAY_MS = 8_000L
        const val FOREGROUND_MIN_GAP_MS = 30_000L
    }
}
