package app.agregateur.core

import app.agregateur.core.party.PartyCodec
import app.agregateur.core.party.PartyEnvelope
import app.agregateur.core.party.PartyMessage
import app.agregateur.core.party.PartyPresence
import app.agregateur.core.sync.ChannelEvent
import app.agregateur.core.sync.RealtimeChannel
import app.agregateur.core.sync.SupabaseConfig
import java.io.File
import java.util.Properties
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue

/**
 * Deux clients sur un canal public du vrai projet Supabase (aucune table nécessaire). Ignoré sauf si LIVE_REALTIME=1 :
 * `$env:LIVE_REALTIME=1; .\gradlew.bat :core:test --tests '*LiveRealtimeCheck*' -i`
 */
class LiveRealtimeCheck {
    @Test
    fun `diffusion et presence entre deux clients`() = runBlocking<Unit> {
        assumeTrue(System.getenv("LIVE_REALTIME") == "1")
        val props = Properties().apply { File("../gradle.properties").reader().use(::load) }
        val config = SupabaseConfig(props.getProperty("watchmate.supabase.url"), props.getProperty("watchmate.supabase.anonKey"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val topic = "wm-check-${UUID.randomUUID()}"
        val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
        fun client(key: String) = RealtimeChannel(config, http, topic, key, private = false, accessToken = { null })
        val a = client("alice")
        val b = client("bob")
        try {
            val aJoined = scope.async { a.events.first { it is ChannelEvent.Joined || it is ChannelEvent.Rejected } }
            val bJoined = scope.async { b.events.first { it is ChannelEvent.Joined || it is ChannelEvent.Rejected } }
            a.start(scope)
            b.start(scope)
            withTimeout(15_000) {
                assertEquals(ChannelEvent.Joined, aJoined.await())
                assertEquals(ChannelEvent.Joined, bJoined.await())
            }
            val bothSeen = scope.async { a.events.filterIsInstance<ChannelEvent.Presence>().first { it.members.keys == setOf("alice", "bob") } }
            a.track(PartyCodec.encodePresence(PartyPresence("alice")))
            b.track(PartyCodec.encodePresence(PartyPresence("bob", watching = true)))
            val presence = withTimeout(15_000) { bothSeen.await() }
            println("presence: ${presence.members}")
            assertEquals("bob", PartyCodec.decodePresence("bob", presence.members.getValue("bob"))!!.name)

            val received = scope.async { b.events.filterIsInstance<ChannelEvent.Broadcast>().first() }
            val sent = PartyEnvelope(from = "alice", m = PartyMessage.Ping(1))
            val start = System.nanoTime()
            a.broadcast("sync", PartyCodec.encode(sent))
            val got = withTimeout(10_000) { received.await() }
            println("diffusion reçue en ${(System.nanoTime() - start) / 1_000_000} ms")
            assertEquals("sync", got.event)
            assertEquals(sent, PartyCodec.decode(got.payload))
        } finally {
            a.close()
            b.close()
            scope.cancel()
        }
    }
}
