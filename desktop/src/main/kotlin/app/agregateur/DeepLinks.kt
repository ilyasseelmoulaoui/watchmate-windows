package app.agregateur

import app.agregateur.core.sync.LinkResult
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Une seule fenêtre WatchMate : un deuxième lancement (souvent par un lien `watchmate://` cliqué dans un e-mail ou
 * un navigateur) transmet ses arguments à la première par une socket locale, puis s'arrête.
 */
class SingleInstance private constructor(private val server: ServerSocket) {
    private val _links = MutableSharedFlow<String>(replay = 4, extraBufferCapacity = 16)
    val links: SharedFlow<String> = _links.asSharedFlow()

    private fun listen() = thread(name = "single-instance", isDaemon = true) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            socket.use { s ->
                s.soTimeout = 2_000
                runCatching {
                    s.getInputStream().bufferedReader().readLines()
                        .filter { it.startsWith(SCHEME) }
                        .forEach { _links.tryEmit(it) }
                    s.getOutputStream().write(HANDSHAKE.toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }
    }

    companion object {
        private const val SCHEME = "watchmate://"
        private const val HANDSHAKE = "watchmate-ok"
        private val portFile = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "WatchMate/instance.port")

        /** Null si une autre fenêtre tourne déjà et a reçu les liens ; sinon cette instance devient la principale. */
        fun claim(args: Array<String>): SingleInstance? {
            val links = args.filter { it.startsWith(SCHEME) }
            val port = runCatching { portFile.readText().trim().toInt() }.getOrNull()
            if (port != null) {
                // La réponse prouve que c'est bien WatchMate qui écoute, et non un programme qui a repris le port.
                val sent = runCatching {
                    Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                        socket.soTimeout = 2_000
                        socket.getOutputStream().bufferedWriter().apply {
                            links.forEach { write(it); newLine() }
                            flush()
                        }
                        socket.shutdownOutput()
                        socket.getInputStream().bufferedReader().readText() == HANDSHAKE
                    }
                }.getOrDefault(false)
                if (sent) return null
            }
            val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            portFile.parentFile.mkdirs()
            portFile.writeText(server.localPort.toString())
            return SingleInstance(server).also { instance ->
                links.forEach { instance._links.tryEmit(it) }
                instance.listen()
            }
        }
    }
}

object DeepLinks {
    /**
     * Associe `watchmate://` à l'exécutable installé (clé utilisateur, sans droits d'administrateur). En
     * développement (`gradlew run`), il n'y a pas d'exécutable à déclarer : rien n'est fait.
     */
    fun register() {
        val exe = System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile } ?: return
        thread(name = "register-scheme", isDaemon = true) {
            val key = "HKCU\\Software\\Classes\\watchmate"
            val command = "\"$exe\" \"%1\""
            listOf(
                listOf("reg", "add", key, "/ve", "/d", "URL:WatchMate", "/f"),
                listOf("reg", "add", key, "/v", "URL Protocol", "/d", "", "/f"),
                listOf("reg", "add", "$key\\shell\\open\\command", "/ve", "/d", command, "/f"),
            ).forEach { args ->
                runCatching { ProcessBuilder(args).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS) }
            }
        }
    }

    fun handle(app: DesktopApp, link: String) {
        val rest = link.removePrefix("watchmate://")
        when {
            rest.startsWith("party") -> app.party.offerInvite(link)
            rest.startsWith("auth") -> app.mainScope.launch {
                val fragment = link.substringAfter('#', "").ifEmpty { link.substringAfter('?', "") }.ifEmpty { null }
                when (app.sync.handleAuthLink(fragment)) {
                    LinkResult.SignedIn -> app.notify("Adresse confirmée : vous êtes connecté.", "Address confirmed: you are signed in.")
                    LinkResult.ChoosePassword -> Unit
                    LinkResult.Expired -> app.notify(
                        "Ce lien a expiré ou a déjà servi. Redemandez un e-mail depuis les Réglages.",
                        "This link has expired or was already used. Request a new email from Settings.",
                    )
                    else -> app.notify(
                        "Ce lien ne correspond à aucune demande faite depuis cet ordinateur.",
                        "This link does not match any request made from this computer.",
                    )
                }
            }
        }
    }
}
