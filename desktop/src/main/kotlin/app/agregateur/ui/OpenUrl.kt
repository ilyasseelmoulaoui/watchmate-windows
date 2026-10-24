package app.agregateur.ui

import java.awt.Desktop
import java.net.URI

fun openUrl(url: String): Boolean = runCatching {
    if (!Desktop.isDesktopSupported()) return false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
    desktop.browse(URI(url))
    true
}.getOrDefault(false)

fun youtubeWatchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
