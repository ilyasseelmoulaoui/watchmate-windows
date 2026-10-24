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
import app.agregateur.sync.SyncManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * Conteneur d'application bureau : mêmes dépôts que Android (`:core`), données sous
 * `%LOCALAPPDATA%/WatchMate`.
 */
class DesktopApp {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dataDir: File = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "WatchMate").also { it.mkdirs() }
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

    fun start() {
        runBlocking { settings.load() }
        appScope.launch {
            listOf(async { modules.load() }, async { library.load() }).awaitAll()
            sync.start()
        }
    }
}
