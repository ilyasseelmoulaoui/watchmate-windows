package app.agregateur.core

import app.agregateur.core.sync.ChannelEvent
import app.agregateur.core.sync.RealtimeChannel
import app.agregateur.core.sync.SupabaseConfig
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** Serveur Phoenix minimal : note ce qu'il reçoit et répond comme Supabase Realtime. */
private class FakeRealtime(private val acceptJoin: Boolean = true) : WebSocketListener() {
    val received = LinkedBlockingQueue<JsonObject>()
    @Volatile var socket: WebSocket? = null

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val msg = Json.parseToJsonElement(text).jsonObject
        received += msg
        if (msg["event"]!!.jsonPrimitive.content == "phx_join") {
            val status = if (acceptJoin) "ok" else "error"
            val response = if (acceptJoin) "{}" else """{"reason":"Unauthorized: You do not have permissions to read from this Channel topic: party:X"}"""
            webSocket.send("""{"event":"phx_reply","payload":{"response":$response,"status":"$status"},"ref":"${msg["ref"]!!.jsonPrimitive.content}","topic":"${msg["topic"]!!.jsonPrimitive.content}"}""")
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }

    fun next(event: String): JsonObject {
        while (true) {
            val msg = received.poll(5, TimeUnit.SECONDS) ?: error("pas de message $event")
            if (msg["event"]!!.jsonPrimitive.content == event) return msg
        }
    }
}

class RealtimeChannelTest {
    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun channel(token: String? = "JWT") = RealtimeChannel(
        SupabaseConfig(server.url("/").toString(), "ANON"),
        OkHttpClient(),
        topic = "party:ABCD2345",
        presenceKey = "u1",
        private = true,
        accessToken = { token },
        heartbeatMs = 200,
        retryDelaysMs = listOf(100),
    )

    @Test
    fun `abonnement, diffusion, presence et battement de coeur`() = runBlocking<Unit> {
        val fake = FakeRealtime()
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        val ch = channel()
        val joined = scope.launch { ch.events.first { it is ChannelEvent.Joined } }
        ch.start(scope)

        val join = fake.next("phx_join")
        assertEquals("realtime:party:ABCD2345", join["topic"]!!.jsonPrimitive.content)
        val payload = join["payload"]!!.jsonObject
        assertEquals("JWT", payload["access_token"]!!.jsonPrimitive.content)
        val config = payload["config"]!!.jsonObject
        assertEquals("true", config["private"]!!.jsonPrimitive.content)
        assertEquals("u1", config["presence"]!!.jsonObject["key"]!!.jsonPrimitive.content)
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/realtime/v1/websocket?apikey=ANON&vsn=1.0.0"), request.path)
        withTimeout(5_000) { joined.join() }

        ch.track(Json.parseToJsonElement("""{"name":"alice","watching":false}""").jsonObject)
        val track = fake.next("presence")["payload"]!!.jsonObject
        assertEquals("track", track["event"]!!.jsonPrimitive.content)
        assertEquals("alice", track["payload"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        assertTrue(ch.broadcast("sync", Json.parseToJsonElement("""{"x":1}""")))
        val sent = fake.next("broadcast")
        assertEquals("""{"type":"broadcast","event":"sync","payload":{"x":1}}""", sent["payload"].toString())
        assertEquals(join["ref"], sent["join_ref"])

        assertEquals("phoenix", fake.next("heartbeat")["topic"]!!.jsonPrimitive.content)

        val events = mutableListOf<ChannelEvent>()
        val collector = scope.launch { ch.events.collect { events += it } }
        Thread.sleep(100)
        val s = fake.socket!!
        s.send("""{"event":"broadcast","payload":{"type":"broadcast","event":"sync","payload":{"y":2}},"ref":null,"topic":"realtime:party:ABCD2345"}""")
        s.send("""{"event":"broadcast","payload":{"type":"broadcast","event":"sync","payload":{"z":3}},"ref":null,"topic":"realtime:other"}""")
        s.send("""{"event":"presence_state","payload":{"u1":{"metas":[{"phx_ref":"A","name":"alice"}]},"u2":{"metas":[{"phx_ref":"B","name":"bob"}]}},"ref":null,"topic":"realtime:party:ABCD2345"}""")
        s.send("""{"event":"presence_diff","payload":{"joins":{"u3":{"metas":[{"phx_ref":"C","name":"carol"}]}},"leaves":{"u2":{"metas":[{"phx_ref":"B","name":"bob"}]}}},"ref":null,"topic":"realtime:party:ABCD2345"}""")
        withTimeout(5_000) {
            while (events.count { it is ChannelEvent.Presence } < 2) kotlinx.coroutines.delay(20)
        }
        collector.cancel()
        val broadcasts = events.filterIsInstance<ChannelEvent.Broadcast>()
        assertEquals(1, broadcasts.size, "autre canal ignoré")
        assertEquals("""{"y":2}""", broadcasts[0].payload.toString())
        val last = events.filterIsInstance<ChannelEvent.Presence>().last()
        assertEquals(setOf("u1", "u3"), last.members.keys)
        assertEquals("carol", last.members["u3"]!!["name"]!!.jsonPrimitive.content)
        ch.close()
    }

    @Test
    fun `reconnexion apres coupure, presence republiee`() = runBlocking<Unit> {
        val first = FakeRealtime()
        val second = FakeRealtime()
        server.enqueue(MockResponse().withWebSocketUpgrade(first))
        server.enqueue(MockResponse().withWebSocketUpgrade(second))
        val ch = channel()
        val events = mutableListOf<ChannelEvent>()
        scope.launch { ch.events.collect { synchronized(events) { events += it } } }
        Thread.sleep(50)
        ch.start(scope)
        first.next("phx_join")
        ch.track(Json.parseToJsonElement("""{"name":"alice"}""").jsonObject)
        first.next("presence")
        first.socket!!.close(1001, "going away")
        second.next("phx_join")
        assertEquals("alice", second.next("presence")["payload"]!!.jsonObject["payload"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val kinds = synchronized(events) { events.map { it::class.simpleName } }
        assertEquals(listOf("Joined", "Disconnected", "Joined"), kinds.filter { it != "Presence" })
        ch.close()
    }

    @Test
    fun `close envoie untrack puis phx_leave`() = runBlocking<Unit> {
        val fake = FakeRealtime()
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        val ch = channel()
        val joined = scope.launch { ch.events.first { it is ChannelEvent.Joined } }
        ch.start(scope)
        fake.next("phx_join")
        withTimeout(5_000) { joined.join() }
        ch.track(Json.parseToJsonElement("""{"name":"alice"}""").jsonObject)
        assertEquals("track", fake.next("presence")["payload"]!!.jsonObject["event"]!!.jsonPrimitive.content)
        ch.close()
        assertEquals("untrack", fake.next("presence")["payload"]!!.jsonObject["event"]!!.jsonPrimitive.content)
        fake.next("phx_leave")
    }

    @Test
    fun `abonnement refuse, pas de nouvelle tentative`() = runBlocking<Unit> {
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeRealtime(acceptJoin = false)))
        val ch = channel()
        val rejected = scope.launch {
            val event = ch.events.first { it !is ChannelEvent.Presence }
            assertIs<ChannelEvent.Rejected>(event)
            assertTrue("Unauthorized" in event.reason)
        }
        ch.start(scope)
        withTimeout(5_000) { rejected.join() }
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
        assertTrue(!ch.broadcast("sync", JsonObject(emptyMap())))
    }
}
