package app.agregateur

import app.agregateur.core.addon.AddonClient
import app.agregateur.core.addon.LocalProxySelector
import app.agregateur.core.catalog.CatalogService
import app.agregateur.core.library.FileLibraryStore
import app.agregateur.core.library.LibraryRepository
import app.agregateur.core.modules.FileModuleStore
import app.agregateur.core.modules.ModuleRepository
import app.agregateur.core.settings.FileSettingsStore
import app.agregateur.core.settings.SettingsRepository
import app.agregateur.core.sync.AccountService
import app.agregateur.core.sync.FilePendingStore
import app.agregateur.core.sync.FileSessionStore
import app.agregateur.core.sync.FileSyncStateStore
import app.agregateur.core.sync.LibraryCollection
import app.agregateur.core.sync.ModuleCollection
import app.agregateur.core.sync.SettingsCollection
import app.agregateur.core.sync.SupabaseClient
import app.agregateur.core.sync.SupabaseConfig
import app.agregateur.core.sync.SyncEngine
import app.agregateur.core.title.TitleService
import app.agregateur.party.PartyManager
import app.agregateur.party.VoiceChat
import app.agregateur.relay.RelayHost
import app.agregateur.sync.SyncManager
import app.agregateur.ui.ImageLoader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.OkHttpClient

/**
 * Conteneur d'application bureau : mêmes dépôts que Android (`:core`), données sous
 * `%LOCALAPPDATA%/WatchMate`.
 */
class DesktopApp {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Fil de l'interface : la salle et le vocal y vivent entièrement, comme sur le fil principal d'Android. */
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Langue de l'interface en cours, pour les textes produits hors des écrans. */
    @Volatile var french: Boolean = true

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Messages courts affichés en bas de la fenêtre (équivalent des toasts Android). */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    fun notify(fr: String, en: String) {
        _notices.tryEmit(if (french) fr else en)
    }

    val dataDir: File = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "WatchMate").also { it.mkdirs() }
    private val cacheDir: File = File(dataDir, "cache").also { it.mkdirs() }

    val addonClient: AddonClient by lazy {
        AddonClient(AddonClient.defaultHttpClient(cacheDir = File(cacheDir, "http")))
    }

    val modules: ModuleRepository by lazy {
        ModuleRepository(addonClient, FileModuleStore(File(dataDir, "modules.json")))
    }

    val library: LibraryRepository by lazy {
        LibraryRepository(FileLibraryStore(File(dataDir, "library.json")))
    }

    val settings: SettingsRepository by lazy {
        SettingsRepository(FileSettingsStore(File(dataDir, "settings.json")))
    }

    val images: ImageLoader by lazy {
        val http = OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "images"), IMAGE_CACHE_BYTES))
            .callTimeout(30, TimeUnit.SECONDS)
            .proxySelector(LocalProxySelector)
            .build()
        ImageLoader(http)
    }

    val catalogs: CatalogService by lazy { CatalogService(addonClient) }
    val titles: TitleService by lazy { TitleService(addonClient) }

    private val supabaseConfig = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)

    private val supabase: SupabaseClient by lazy {
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).proxySelector(LocalProxySelector).build()
        SupabaseClient(supabaseConfig, http)
    }

    val account: AccountService by lazy {
        AccountService(
            supabase,
            FileSessionStore(File(dataDir, "session.json")),
            FilePendingStore(File(dataDir, "auth_pending.json")),
        )
    }

    val sync: SyncManager by lazy {
        val engine = SyncEngine(
            listOf(LibraryCollection(library), ModuleCollection(modules), SettingsCollection(settings)),
            FileSyncStateStore(File(dataDir, "sync_state.json")),
        )
        SyncManager(account, engine, supabase, library, modules, settings, appScope)
    }

    val relay: RelayHost by lazy { RelayHost(cacheDir) }

    val party: PartyManager by lazy {
        val realtime = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).proxySelector(LocalProxySelector).build()
        PartyManager(account, supabase, supabaseConfig, realtime, mainScope, VoiceChat())
    }

    fun start() {
        runBlocking { settings.load() }
        appScope.launch {
            listOf(async { modules.load() }, async { library.load() }).awaitAll()
            sync.start()
        }
        mainScope.launch { party.start() }
    }

    fun close() {
        runCatching { relay.close() }
    }

    private companion object {
        const val IMAGE_CACHE_BYTES = 200L * 1024 * 1024
    }
}
