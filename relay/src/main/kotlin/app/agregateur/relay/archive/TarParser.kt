package app.agregateur.relay.archive

import app.agregateur.relay.RelayException

/** TAR (ustar, noms longs GNU et en-têtes pax) : les fichiers y sont toujours stockés tels quels. */
object TarParser : ArchiveParser {
    private const val BLOCK = 512
    private const val MAX_ENTRIES = 100_000
    private const val MAX_META = 1024 * 1024

    override fun entries(volumes: Volumes): List<ArchiveEntry> {
        val entries = mutableListOf<ArchiveEntry>()
        val size = volumes.size(0)
        var pos = 0L
        var longName: String? = null
        var pax = emptyMap<String, String>()
        repeat(MAX_ENTRIES) {
            if (pos + BLOCK > size) return entries
            val header = volumes.read(0, pos, BLOCK)
            if (header.size < BLOCK || header.all { it == 0.toByte() }) return entries
            val recordSize = sizeField(header)
                ?: if (entries.isEmpty()) throw RelayException.unsupported("Archive TAR illisible") else return entries
            val dataPos = pos + BLOCK
            var dataSize = recordSize
            when (header[156].toInt().toChar()) {
                'L' -> longName = cString(volumes.read(0, dataPos, metaLength(recordSize)), 0, recordSize.toInt())
                'x' -> pax = paxRecords(volumes.read(0, dataPos, metaLength(recordSize)))
                '0', '\u0000', '7' -> {
                    val plain = cString(header, 0, 100)
                    val prefix = if (String(header, 257, 5, Charsets.US_ASCII) == "ustar") cString(header, 345, 155) else ""
                    val name = pax["path"] ?: longName ?: if (prefix.isEmpty()) plain else "$prefix/$plain"
                    val fileSize = pax["size"]?.toLongOrNull() ?: recordSize
                    dataSize = fileSize
                    entries += ArchiveEntry(name, fileSize, stored = true, segments = listOf(Segment(0, dataPos, fileSize)))
                    longName = null
                    pax = emptyMap()
                }
                'g' -> Unit
                else -> {
                    longName = null
                    pax = emptyMap()
                }
            }
            pos = dataPos + (dataSize + BLOCK - 1) / BLOCK * BLOCK
        }
        return entries
    }

    private fun metaLength(recordSize: Long): Int {
        if (recordSize > MAX_META) throw RelayException.unsupported("En-tête TAR trop grand")
        return recordSize.toInt()
    }

    /** Taille en octal, ou en binaire (base 256) quand le premier octet a son bit haut. */
    private fun sizeField(header: ByteArray): Long? {
        if (header[124].toInt() and 0x80 != 0) {
            var value = 0L
            for (i in 125 until 136) value = (value shl 8) or (header[i].toLong() and 0xFF)
            return value
        }
        val text = String(header, 124, 12, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
        return if (text.isEmpty()) 0L else text.toLongOrNull(8)
    }

    private fun cString(data: ByteArray, from: Int, max: Int): String {
        val end = (from until minOf(data.size, from + max)).firstOrNull { data[it] == 0.toByte() } ?: minOf(data.size, from + max)
        return String(data, from, end - from, Charsets.UTF_8)
    }

    /** Enregistrements pax : « <longueur> <clé>=<valeur>\n ». */
    private fun paxRecords(data: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var at = 0
        while (at < data.size) {
            val space = (at until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: break
            val length = String(data, at, space - at, Charsets.US_ASCII).toIntOrNull() ?: break
            if (length <= 0 || at + length > data.size) break
            val record = String(data, space + 1, at + length - space - 2, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            at += length
        }
        return out
    }
}
