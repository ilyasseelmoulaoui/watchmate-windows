package app.agregateur.core.addon

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Une adresse de bouclage désigne toujours cet appareil : elle ne passe jamais par un proxy configuré
 * (sinon le proxy la comprendrait comme sa propre machine). Les autres adresses suivent le réglage du système.
 */
object LocalProxySelector : ProxySelector() {
    private val loopback = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    override fun select(uri: URI?): List<Proxy> {
        if (uri?.host?.lowercase() in loopback) return listOf(Proxy.NO_PROXY)
        val system = getDefault()?.takeIf { it !== this } ?: return listOf(Proxy.NO_PROXY)
        return system.select(uri)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        getDefault()?.takeIf { it !== this }?.connectFailed(uri, sa, ioe)
    }
}
