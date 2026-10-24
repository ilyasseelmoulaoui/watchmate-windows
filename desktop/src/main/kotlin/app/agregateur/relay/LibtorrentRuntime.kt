package app.agregateur.relay

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * libtorrent4j attend la DLL native sur le chemin JNI (`libtorrent4j.jni.path`) ou dans `java.library.path`.
 * Le jar Windows ne fait que l'embarquer en `lib/x86_64/libtorrent4j.dll` : on l'extrait une fois au cache.
 * À appeler avant toute construction de `SessionManager`.
 */
object LibtorrentRuntime {
    @Volatile private var ready = false

    fun ensureLoaded() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val existing = System.getProperty(JNI_PATH_PROP)?.takeIf { File(it).isFile }
            val dll = existing?.let(::File) ?: extract()
            System.setProperty(JNI_PATH_PROP, dll.absolutePath)
            ready = true
        }
    }

    private fun extract(): File {
        val resource = RESOURCE_CANDIDATES.firstNotNullOfOrNull { path ->
            LibtorrentRuntime::class.java.classLoader.getResourceAsStream(path)?.let { path to it }
        } ?: error("DLL libtorrent4j introuvable dans le classpath (artifact libtorrent4j-windows).")

        val dir = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "WatchMate/native").also { it.mkdirs() }
        val target = File(dir, "libtorrent4j.dll")
        resource.second.use { input ->
            Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    private const val JNI_PATH_PROP = "libtorrent4j.jni.path"

    private val RESOURCE_CANDIDATES = listOf(
        "lib/x86_64/libtorrent4j.dll",
        "lib/amd64/libtorrent4j.dll",
        "libtorrent4j.dll",
    )
}
