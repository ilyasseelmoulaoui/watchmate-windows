package app.agregateur.relay.http

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HttpRequest(val method: String, val target: String, val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** Réponse en cours : les en-têtes partent une fois, puis le corps. */
class HttpResponder internal constructor(private val out: OutputStream) {
    var committed = false
        private set

    fun send(status: Int, headers: Map<String, String>, body: ((OutputStream) -> Unit)? = null) {
        check(!committed)
        committed = true
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        body?.invoke(out)
        out.flush()
    }

    fun text(status: Int, message: String) {
        val bytes = message.toByteArray()
        send(status, mapOf("Content-Type" to "text/plain; charset=utf-8", "Content-Length" to bytes.size.toString())) {
            it.write(bytes)
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        415 -> "Unsupported Media Type"
        416 -> "Range Not Satisfiable"
        502 -> "Bad Gateway"
        504 -> "Gateway Timeout"
        else -> "Error"
    }
}

/**
 * Petit serveur HTTP/1.1 (GET et HEAD) qui n'écoute que sur l'adresse de bouclage de l'appareil :
 * il n'est jamais joignable depuis le réseau (SPEC §7, sécurité du relais). Une connexion = une requête.
 */
class LocalHttpServer(private val handler: (HttpRequest, HttpResponder) -> Unit) : Closeable {
    // 127.0.0.1 explicitement : sur Android, getLoopbackAddress() renvoie ::1, que les adresses du relais n'utilisent pas.
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val counter = AtomicInteger()
    private val workers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "relay-http-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    val port: Int get() = server.localPort

    fun start(): LocalHttpServer {
        Thread({ acceptLoop() }, "relay-accept").apply { isDaemon = true }.start()
        return this
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            workers.execute { serve(socket) }
        }
    }

    private fun serve(socket: Socket) = socket.use {
        try {
            it.soTimeout = READ_TIMEOUT_MS
            val request = readRequest(it.getInputStream()) ?: return
            val responder = HttpResponder(BufferedOutputStream(it.getOutputStream(), 64 * 1024))
            if (request.method != "GET" && request.method != "HEAD") {
                responder.text(405, "GET ou HEAD seulement")
                return
            }
            try {
                handler(request, responder)
            } catch (e: SocketException) {
                // Le lecteur a fermé la connexion (changement de position, arrêt) : rien à signaler.
            } catch (e: Exception) {
                if (!responder.committed) responder.text(500, e.message ?: "erreur")
            }
        } catch (_: IOException) {
        }
    }

    override fun close() {
        server.close()
        workers.shutdownNow()
    }

    private companion object {
        const val BACKLOG = 50
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_HEAD = 16 * 1024

        fun readRequest(input: InputStream): HttpRequest? {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (++total > MAX_HEAD) return null
                when (b) {
                    '\r'.code -> continue
                    '\n'.code -> {
                        if (line.isEmpty()) break
                        lines += line.toString()
                        line.clear()
                    }
                    else -> line.append(b.toChar())
                }
            }
            val parts = lines.firstOrNull()?.split(' ') ?: return null
            if (parts.size != 3) return null
            val headers = lines.drop(1).mapNotNull { h ->
                val colon = h.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                h.substring(0, colon).trim().lowercase() to h.substring(colon + 1).trim()
            }.toMap()
            return HttpRequest(parts[0], parts[1], headers)
        }
    }
}
