package app.agregateur.core

import app.agregateur.core.addon.LocalProxySelector
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalProxySelectorTest {
    private val previous = ProxySelector.getDefault()
    private val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.lan", 8888))

    @AfterTest
    fun restore() = ProxySelector.setDefault(previous)

    @Test
    fun `bouclage toujours direct, le reste suit le proxy du systeme`() {
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?) = listOf(proxy)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        })
        listOf("http://127.0.0.1:4000/x", "http://localhost:8765/m", "http://[::1]:1/").forEach {
            assertEquals(listOf(Proxy.NO_PROXY), LocalProxySelector.select(URI(it)), it)
        }
        assertEquals(listOf(proxy), LocalProxySelector.select(URI("https://exemple.org/v.mp4")))
    }
}
