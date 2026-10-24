package app.agregateur.core.sync

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface ChannelEvent {
    /** Abonnement accepté (aussi après chaque reconnexion). */
    data object Joined : ChannelEvent

    data class Broadcast(val event: String, val payload: JsonElement) : ChannelEvent

    /** Présence complète : une entrée par clé (la plus récente si un même compte est connecté deux fois). */
    data class Presence(val members: Map<String, JsonObject>) : ChannelEvent

    /** Connexion perdue ; une nouvelle tentative suit. */
    data class Disconnected(val reason: String) : ChannelEvent

    /** Abonnement refusé par le serveur (droits) : pas de nouvelle tentative. */
    data class Rejected(val reason: String) : ChannelEvent
}

/**
 * Un canal Supabase Realtime (protocole Phoenix, JSON v1) sur une WebSocket OkHttp : diffusion (« broadcast ») et
 * présence. Reconnexion automatique ; ce qui est envoyé pendant une coupure est perdu (la synchronisation s'en remet).
 * Pour un canal privé, les droits sont vérifiés par les règles RLS de `realtime.messages`.
 */
class RealtimeChannel(
    private val config: SupabaseConfig,
    private val http: OkHttpClient,
    /** Sans le préfixe `realtime:` (c'est ce que renvoie `realtime.topic()` côté SQL). */
    val topic: String,
    private val presenceKey: String,
    private val private: Boolean,
    /** Jeton d'accès de l'utilisateur (renouvelé au besoin) ; null : clé publique seule. */
    private val accessToken: suspend () -> String?,
    private val heartbeatMs: Long = 25_000,
    private val tokenRefreshMs: Long = 300_000,
    private val retryDelaysMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val fullTopic = "realtime:$topic"
    private val refs = AtomicLong(0)
    private val _events = MutableSharedFlow<ChannelEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ChannelEvent> = _events.asSharedFlow()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var joinRef: String? = null

    @Volatile
    private var joined = false

    @Volatile
    private var presencePayload: JsonObject? = null
    private val presence = mutableMapOf<String, MutableMap<String, JsonObject>>()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                when (val end = connectOnce { attempt = 0 }) {
                    is End.Rejected -> {
                        _events.emit(ChannelEvent.Rejected(end.reason))
                        return@launch
                    }
                    is End.Lost -> _events.emit(ChannelEvent.Disconnected(end.reason))
                }
                delay(retryDelaysMs[attempt.coerceAtMost(retryDelaysMs.lastIndex)])
                attempt++
            }
        }
    }

    /** Diffuse [payload] aux autres membres ; faux si le canal n'est pas connecté. */
    fun broadcast(event: String, payload: JsonElement): Boolean = push(
        "broadcast",
        buildJsonObject {
            put("type", "broadcast")
            put("event", event)
            put("payload", payload)
        },
    )

    /** Publie (ou met à jour) ce que ce membre affiche dans la présence ; republié à chaque reconnexion. */
    fun track(payload: JsonObject) {
        presencePayload = payload
        sendTrack()
    }

    suspend fun close() {
        if (joined) {
            // Untrack avant phx_leave : sinon les autres gardent le membre jusqu'au timeout de présence.
            if (presencePayload != null) {
                push(
                    "presence",
                    buildJsonObject {
                        put("type", "presence")
                        put("event", "untrack")
                    },
                )
                presencePayload = null
            }
            push("phx_leave", JsonObject(emptyMap()))
            delay(100)
        }
        joined = false
        socket?.close(1000, "leave")
        job?.cancelAndJoin()
        job = null
    }

    private sealed interface End {
        data class Lost(val reason: String) : End
        data class Rejected(val reason: String) : End
    }

    private suspend fun connectOnce(onJoined: () -> Unit): End = coroutineScope {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val closed = CompletableDeferred<String>()
        val url = config.url.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("realtime/v1/websocket")
            .addQueryParameter("apikey", config.anonKey)
            .addQueryParameter("vsn", "1.0.0")
            .build()
        val token = runCatching { accessToken() }.getOrNull()
        val ws = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    incoming.trySend(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    closed.complete("closed $code $reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete("closed $code $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed.complete(t.message ?: t.javaClass.simpleName)
                }
            },
        )
        socket = ws
        joined = false
        synchronized(presence) { presence.clear() }
        val ref = nextRef()
        joinRef = ref
        ws.send(
            frame(fullTopic, "phx_join", ref, ref) {
                putJsonObject("config") {
                    putJsonObject("broadcast") {
                        put("self", false)
                        put("ack", false)
                    }
                    putJsonObject("presence") { put("key", presenceKey) }
                    putJsonArray("postgres_changes") {}
                    put("private", private)
                }
                token?.let { put("access_token", it) }
            },
        )
        val heartbeat = launch {
            while (true) {
                delay(heartbeatMs)
                ws.send(frame("phoenix", "heartbeat", nextRef(), null) {})
            }
        }
        val refresher = launch {
            var last = token
            while (true) {
                delay(tokenRefreshMs)
                val fresh = runCatching { accessToken() }.getOrNull()
                if (fresh != null && fresh != last) {
                    last = fresh
                    push("access_token", buildJsonObject { put("access_token", fresh) })
                }
            }
        }
        val end: End = try {
            var result: End? = null
            while (result == null) {
                result = select {
                    closed.onAwait { End.Lost(it) }
                    incoming.onReceive { text -> handle(text, ref, onJoined) }
                }
            }
            result
        } finally {
            heartbeat.cancel()
            refresher.cancel()
            joined = false
            socket = null
            ws.cancel()
        }
        end
    }

    /** Traite un message du serveur ; renvoie une fin de connexion si le canal est refusé ou fermé. */
    private suspend fun handle(text: String, ref: String, onJoined: () -> Unit): End? {
        val message = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        val event = message.string("event")
        if (message.string("topic") != fullTopic) return null
        val payload = message["payload"] as? JsonObject ?: JsonObject(emptyMap())
        when (event) {
            "phx_reply" -> if (message.string("ref") == ref) {
                if (payload.string("status") == "ok") {
                    joined = true
                    onJoined()
                    _events.emit(ChannelEvent.Joined)
                    sendTrack()
                } else {
                    val response = payload["response"] as? JsonObject
                    return End.Rejected(response?.string("reason") ?: response?.toString() ?: "join refused")
                }
            }
            "broadcast" -> {
                val name = payload.string("event") ?: return null
                _events.emit(ChannelEvent.Broadcast(name, payload["payload"] ?: JsonObject(emptyMap())))
            }
            "presence_state" -> {
                synchronized(presence) {
                    presence.clear()
                    addPresence(payload)
                }
                emitPresence()
            }
            "presence_diff" -> {
                synchronized(presence) {
                    (payload["leaves"] as? JsonObject)?.forEach { (key, value) ->
                        val metas = presence[key] ?: return@forEach
                        metasOf(value).forEach { metas.remove(it.string("phx_ref").orEmpty()) }
                        if (metas.isEmpty()) presence.remove(key)
                    }
                    (payload["joins"] as? JsonObject)?.let(::addPresence)
                }
                emitPresence()
            }
            "system" -> if (payload.string("status") == "error") {
                return End.Rejected(payload.string("message") ?: "error")
            }
            "phx_error" -> return End.Lost("channel error")
            "phx_close" -> return End.Lost("channel closed")
        }
        return null
    }

    private fun addPresence(state: JsonObject) {
        state.forEach { (key, value) ->
            val metas = presence.getOrPut(key) { linkedMapOf() }
            metasOf(value).forEach { metas[it.string("phx_ref").orEmpty()] = it }
        }
    }

    private suspend fun emitPresence() {
        val members = synchronized(presence) { presence.mapValues { (_, metas) -> metas.values.last() } }
        _events.emit(ChannelEvent.Presence(members))
    }

    private fun metasOf(value: JsonElement): List<JsonObject> =
        ((value as? JsonObject)?.get("metas") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    private fun sendTrack() {
        val payload = presencePayload ?: return
        if (!joined) return
        push(
            "presence",
            buildJsonObject {
                put("type", "presence")
                put("event", "track")
                put("payload", payload)
            },
        )
    }

    private fun push(event: String, payload: JsonObject): Boolean {
        val ws = socket ?: return false
        if (!joined && event != "phx_leave") return false
        return ws.send(frame(fullTopic, event, nextRef(), joinRef) { payload.forEach { (k, v) -> put(k, v) } })
    }

    private fun nextRef() = refs.incrementAndGet().toString()

    private fun frame(
        topic: String,
        event: String,
        ref: String,
        joinRef: String?,
        payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): String = buildJsonObject {
        put("topic", topic)
        put("event", event)
        put("payload", buildJsonObject(payload))
        put("ref", ref)
        joinRef?.let { put("join_ref", it) }
    }.toString()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
