package app.agregateur.relay.archive

import app.agregateur.relay.RelayException

/**
 * Lecture des en-têtes RAR 4 et RAR 5, d'après la description publique du format.
 * Seuls les fichiers stockés sans compression ni chiffrement sont lisibles ; un fichier peut s'étendre sur plusieurs volumes.
 */
object RarParser : ArchiveParser {
    private val RAR4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val RAR5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    private const val MAX_HEADERS = 10_000

    /** Une part de fichier trouvée dans un volume. */
    private class Part(
        val name: String,
        val packed: Long,
        val unpacked: Long,
        val dataOffset: Long,
        val stored: Boolean,
        val directory: Boolean,
        val continuesPrevious: Boolean,
    )

    override fun entries(volumes: Volumes): List<ArchiveEntry> {
        val entries = mutableListOf<MutableEntry>()
        for (volume in 0 until volumes.count) {
            val signature = volumes.read(volume, 0, 8)
            val parts = when {
                signature.startsWith(RAR5) -> rar5Parts(volumes, volume)
                signature.startsWith(RAR4) -> rar4Parts(volumes, volume)
                else -> throw RelayException.unsupported("Le volume ${volume + 1} n'est pas une archive RAR")
            }
            parts.filterNot { it.directory }.forEach { part ->
                val last = entries.lastOrNull()
                val segment = Segment(volume, part.dataOffset, part.packed)
                if (part.continuesPrevious && last != null && last.name == part.name) {
                    last.segments += segment
                    last.stored = last.stored && part.stored
                } else {
                    entries += MutableEntry(part.name, part.unpacked, part.stored, mutableListOf(segment))
                }
            }
        }
        return entries.map { e ->
            // Un fichier stocké tient en entier dans ses morceaux : sinon un volume manque.
            val complete = e.segments.sumOf { it.length } == e.size
            ArchiveEntry(e.name, e.size, e.stored && complete, e.segments)
        }
    }

    private class MutableEntry(val name: String, val size: Long, var stored: Boolean, val segments: MutableList<Segment>)

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun rar4Parts(volumes: Volumes, volume: Int): List<Part> {
        val parts = mutableListOf<Part>()
        val size = volumes.size(volume)
        var pos = RAR4.size.toLong()
        repeat(MAX_HEADERS) {
            if (pos + 7 > size) return parts
            val head = Bytes(volumes.read(volume, pos, 11))
            if (head.size < 7) return parts
            val type = head.u8(2)
            val flags = head.u16(3)
            val headSize = head.u16(5)
            if (headSize < 7) throw RelayException.unsupported("En-tête RAR invalide")
            when (type) {
                0x73 -> if (flags and 0x0080 != 0) throw RelayException.unsupported("Archive RAR chiffrée")
                0x7B -> return parts
            }
            var dataSize = if ((flags and 0x8000 != 0 || type == 0x74 || type == 0x7A) && head.size >= 11) head.u32(7) else 0L
            if (type == 0x74) {
                val h = Bytes(volumes.read(volume, pos, headSize))
                if (h.size < 32) throw RelayException.unsupported("En-tête de fichier RAR tronqué")
                var unpacked = h.u32(11)
                var nameAt = 32
                if (flags and 0x0100 != 0) {
                    dataSize = dataSize or (h.u32(32) shl 32)
                    unpacked = unpacked or (h.u32(36) shl 32)
                    nameAt = 40
                }
                val nameSize = h.u16(26)
                val rawName = h.data.copyOfRange(nameAt, minOf(h.size, nameAt + nameSize))
                // Avec un nom Unicode (drapeau 0x200), la forme simple précède un octet nul.
                val simple = rawName.takeWhile { it != 0.toByte() }.toByteArray()
                parts += Part(
                    name = String(simple, Charsets.UTF_8).replace('\\', '/'),
                    packed = dataSize,
                    unpacked = unpacked,
                    dataOffset = pos + headSize,
                    stored = h.u8(25) == 0x30 && flags and 0x0004 == 0,
                    directory = flags and 0x00E0 == 0x00E0,
                    continuesPrevious = flags and 0x0001 != 0,
                )
            }
            pos += headSize + dataSize
        }
        return parts
    }

    private fun rar5Parts(volumes: Volumes, volume: Int): List<Part> {
        val parts = mutableListOf<Part>()
        val size = volumes.size(volume)
        var pos = RAR5.size.toLong()
        repeat(MAX_HEADERS) {
            if (pos + 7 > size) return parts
            val prefix = VintReader(volumes.read(volume, pos, 7), 4)
            val headerSize = prefix.next()
            val headerStart = pos + prefix.at
            if (headerSize <= 0 || headerSize > 2 * 1024 * 1024) throw RelayException.unsupported("En-tête RAR 5 invalide")
            val h = VintReader(volumes.read(volume, headerStart, headerSize.toInt()), 0)
            val type = h.next()
            val flags = h.next()
            val extraSize = if (flags and 0x1L != 0L) h.next() else 0L
            val dataSize = if (flags and 0x2L != 0L) h.next() else 0L
            val dataStart = headerStart + headerSize
            when (type) {
                4L -> throw RelayException.unsupported("Archive RAR chiffrée")
                5L -> return parts
                2L -> {
                    val fileFlags = h.next()
                    val unpacked = h.next()
                    h.next() // attributs
                    if (fileFlags and 0x2L != 0L) h.skip(4)
                    if (fileFlags and 0x4L != 0L) h.skip(4)
                    val compression = h.next()
                    h.next() // système d'origine
                    val nameLength = h.next().toInt()
                    val name = h.string(nameLength)
                    val encrypted = extraSize > 0 && hasEncryptionRecord(h.data, (headerSize - extraSize).toInt(), headerSize.toInt())
                    parts += Part(
                        name = name,
                        packed = dataSize,
                        unpacked = unpacked,
                        dataOffset = dataStart,
                        stored = (compression shr 7) and 0x7 == 0L && !encrypted,
                        directory = fileFlags and 0x1L != 0L,
                        continuesPrevious = flags and 0x8L != 0L,
                    )
                }
            }
            pos = dataStart + dataSize
        }
        return parts
    }

    private fun hasEncryptionRecord(header: ByteArray, from: Int, to: Int): Boolean {
        val r = VintReader(header, from)
        while (r.at < to) {
            val recordSize = r.next()
            val recordStart = r.at
            if (r.next() == 0x01L) return true
            r.at = (recordStart + recordSize).toInt()
            if (recordSize <= 0) return false
        }
        return false
    }

    /** Entiers de taille variable de RAR 5 : 7 bits par octet, le bit haut annonce la suite. */
    private class VintReader(val data: ByteArray, var at: Int) {
        fun next(): Long {
            var value = 0L
            var shift = 0
            while (at < data.size && shift < 64) {
                val b = data[at++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
            throw RelayException.unsupported("En-tête RAR 5 tronqué")
        }

        fun skip(n: Int) {
            at += n
        }

        fun string(length: Int): String {
            if (at + length > data.size) throw RelayException.unsupported("En-tête RAR 5 tronqué")
            return String(data, at, length, Charsets.UTF_8).also { at += length }
        }
    }
}
