package app.agregateur.relay.archive

import app.agregateur.relay.RelayException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Les volumes d'une archive, lus par plages d'octets. */
interface Volumes {
    val count: Int
    fun size(volume: Int): Long

    /** Au plus [length] octets à partir de [position] ; moins si la fin du volume est atteinte. */
    fun read(volume: Int, position: Long, length: Int): ByteArray

    /** Flux des octets [position, end[ du volume. */
    fun stream(volume: Int, position: Long, end: Long): InputStream
}

/** Volumes distants, lus avec des requêtes `Range`. Un serveur qui ignore `Range` est refusé : il faudrait tout télécharger. */
class RemoteVolumes(private val client: OkHttpClient, private val urls: List<String>) : Volumes {
    private val sizes = ConcurrentHashMap<Int, Long>()

    override val count: Int get() = urls.size

    override fun size(volume: Int): Long = sizes.getOrPut(volume) {
        call(volume, "bytes=0-0").use { response ->
            totalFromContentRange(response.header("Content-Range"))
                ?: throw RelayException.upstream("Taille inconnue pour le volume ${volume + 1}")
        }
    }

    override fun read(volume: Int, position: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val size = size(volume)
        if (position >= size) return ByteArray(0)
        val end = minOf(size, position + length)
        return stream(volume, position, end).use { it.readNBytes((end - position).toInt()) }
    }

    override fun stream(volume: Int, position: Long, end: Long): InputStream {
        val response = call(volume, "bytes=$position-${end - 1}")
        return response.body?.byteStream() ?: run {
            response.close()
            throw RelayException.upstream("Réponse vide")
        }
    }

    private fun call(volume: Int, range: String): Response {
        val request = Request.Builder().url(urls[volume]).header("Range", range).build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw RelayException.upstream("Volume ${volume + 1} injoignable : ${e.message}")
        }
        if (response.code != 206) {
            response.close()
            throw RelayException.upstream("Le serveur du volume ${volume + 1} ne permet pas la lecture par plages (${response.code})")
        }
        return response
    }

    private fun totalFromContentRange(value: String?): Long? =
        value?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
}

/** Garde en mémoire les derniers blocs lus : l'analyse des en-têtes fait beaucoup de petites lectures voisines. */
class BlockCache(private val inner: Volumes, private val blockSize: Int = 64 * 1024, private val maxBlocks: Int = 32) : Volumes by inner {
    private val blocks = object : LinkedHashMap<Pair<Int, Long>, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Long>, ByteArray>?) = size > maxBlocks
    }

    override fun read(volume: Int, position: Long, length: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(length)
        var pos = position
        while (out.size() < length) {
            val index = pos / blockSize
            val block = synchronized(blocks) { blocks[volume to index] }
                ?: inner.read(volume, index * blockSize, blockSize).also { synchronized(blocks) { blocks[volume to index] = it } }
            val inBlock = (pos - index * blockSize).toInt()
            if (inBlock >= block.size) break
            val n = minOf(block.size - inBlock, length - out.size())
            out.write(block, inBlock, n)
            pos += n
            if (block.size < blockSize) break
        }
        return out.toByteArray()
    }
}

/** Petite lecture d'entiers little-endian dans un tableau d'octets. */
internal class Bytes(val data: ByteArray) {
    fun u8(at: Int): Int = data[at].toInt() and 0xFF
    fun u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
    fun u32(at: Int): Long = (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
    fun u64(at: Int): Long = u32(at) or (u32(at + 4) shl 32)
    val size: Int get() = data.size
}
