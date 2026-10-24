package app.agregateur.relay.archive

import app.agregateur.relay.RelayException

/** ZIP (et ZIP64) en un seul volume, d'après la spécification publique APPNOTE. Seuls les fichiers stockés sont lisibles. */
object ZipParser : ArchiveParser {
    private const val EOCD = 0x06054b50L
    private const val EOCD64_LOCATOR = 0x07064b50L
    private const val EOCD64 = 0x06064b50L
    private const val CENTRAL = 0x02014b50L
    private const val LOCAL = 0x04034b50L
    private const val MAX_CENTRAL_DIRECTORY = 32L * 1024 * 1024
    private const val U32_MAX = 0xFFFFFFFFL

    override fun entries(volumes: Volumes): List<ArchiveEntry> {
        val size = volumes.size(0)
        val tailLength = minOf(size, 22L + 65_535).toInt()
        val tailStart = size - tailLength
        val tail = Bytes(volumes.read(0, tailStart, tailLength))
        val eocd = (tail.size - 22 downTo 0).firstOrNull { tail.u32(it) == EOCD }
            ?: throw RelayException.unsupported("Archive ZIP illisible (fin de répertoire introuvable)")
        var cdSize = tail.u32(eocd + 12)
        var cdOffset = tail.u32(eocd + 16)
        if (cdOffset == U32_MAX || cdSize == U32_MAX || tail.u16(eocd + 10) == 0xFFFF) {
            val locator = eocd - 20
            if (locator < 0 || tail.u32(locator) != EOCD64_LOCATOR) throw RelayException.unsupported("ZIP64 incomplet")
            val record = Bytes(volumes.read(0, tail.u64(locator + 8), 56))
            if (record.size < 56 || record.u32(0) != EOCD64) throw RelayException.unsupported("ZIP64 incomplet")
            cdSize = record.u64(40)
            cdOffset = record.u64(48)
        }
        if (cdSize > MAX_CENTRAL_DIRECTORY || cdOffset + cdSize > size) throw RelayException.unsupported("Répertoire ZIP invalide")
        val cd = Bytes(volumes.read(0, cdOffset, cdSize.toInt()))
        val entries = mutableListOf<ArchiveEntry>()
        var at = 0
        while (at + 46 <= cd.size && cd.u32(at) == CENTRAL) {
            val flags = cd.u16(at + 8)
            val method = cd.u16(at + 10)
            var compressed = cd.u32(at + 20)
            var uncompressed = cd.u32(at + 24)
            val nameLength = cd.u16(at + 28)
            val extraLength = cd.u16(at + 30)
            val commentLength = cd.u16(at + 32)
            var localOffset = cd.u32(at + 42)
            val name = String(cd.data, at + 46, minOf(nameLength, cd.size - at - 46), Charsets.UTF_8)
            // Champ ZIP64 : les valeurs à 0xFFFFFFFF y sont données en 64 bits, dans cet ordre.
            var extra = at + 46 + nameLength
            val extraEnd = minOf(extra + extraLength, cd.size)
            while (extra + 4 <= extraEnd) {
                val id = cd.u16(extra)
                val length = cd.u16(extra + 2)
                if (id == 0x0001) {
                    var v = extra + 4
                    if (uncompressed == U32_MAX && v + 8 <= extraEnd) uncompressed = cd.u64(v).also { v += 8 }
                    if (compressed == U32_MAX && v + 8 <= extraEnd) compressed = cd.u64(v).also { v += 8 }
                    if (localOffset == U32_MAX && v + 8 <= extraEnd) localOffset = cd.u64(v)
                }
                extra += 4 + length
            }
            if (!name.endsWith("/")) {
                val stored = method == 0 && flags and 0x1 == 0 && compressed == uncompressed
                entries += ArchiveEntry(name, uncompressed, stored, listOf(Segment(0, localOffset, compressed)))
            }
            at += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }

    /** L'index donne l'en-tête local ; les données commencent juste après lui, dont la taille varie. */
    override fun locate(volumes: Volumes, entry: ArchiveEntry): ArchiveEntry {
        val segment = entry.segments.single()
        val local = Bytes(volumes.read(0, segment.offset, 30))
        if (local.size < 30 || local.u32(0) != LOCAL) throw RelayException.unsupported("En-tête ZIP local invalide")
        val dataOffset = segment.offset + 30 + local.u16(26) + local.u16(28)
        return entry.copy(segments = listOf(segment.copy(offset = dataOffset)))
    }
}
