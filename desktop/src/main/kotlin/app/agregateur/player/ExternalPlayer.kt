package app.agregateur.player

import app.agregateur.DesktopApp
import app.agregateur.relay.RelayHost
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Passe une source au lecteur vidéo choisi dans Windows (F5 sur Android) : une liste de lecture `.m3u` d'une
 * ligne, ouverte par l'application associée à ce format (VLC, MPC-HC, Lecteur multimédia…).
 */
fun openInOtherPlayer(url: String, title: String): Boolean = runCatching {
    val file = File.createTempFile("watchmate-", ".m3u").apply { deleteOnExit() }
    file.writeText("#EXTM3U\n#EXTINF:-1,${title.replace('\n', ' ')}\n$url\n", Charsets.UTF_8)
    Desktop.getDesktop().open(file)
    true
}.getOrDefault(false)

/** Ouvre une adresse dans le navigateur par défaut. */
fun openLink(url: String): Boolean = runCatching {
    Desktop.getDesktop().browse(URI(url))
    true
}.getOrDefault(false)

/**
 * Lecture dans un autre lecteur, relais compris : le bail est gardé jusqu'à la fermeture de WatchMate (on ne sait
 * pas quand l'autre lecteur aura fini). Renvoie faux si rien n'a pu être ouvert.
 */
suspend fun openExternally(app: DesktopApp, request: PlayRequest): Boolean {
    val target = request.relayTarget
    val url = if (target == null) {
        request.url
    } else {
        externalLeases += app.relay.acquire()
        runCatching { app.relay.endpoint().url(target) }.getOrNull() ?: run {
            app.notify("Le relais local ne répond pas.", "The local relay is not responding.")
            return false
        }
    }
    val ok = withContext(Dispatchers.IO) { openInOtherPlayer(url, request.title) }
    if (!ok) app.notify("Aucune application ne sait ouvrir cette vidéo.", "No application can open this video.")
    return ok
}

private val externalLeases = mutableListOf<RelayHost.Lease>()
