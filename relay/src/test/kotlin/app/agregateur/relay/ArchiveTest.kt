package app.agregateur.relay

import app.agregateur.core.playback.ArchiveKind
import app.agregateur.relay.archive.ArchiveMediaSource
import app.agregateur.relay.archive.BlockCache
import app.agregateur.relay.archive.Volumes
import app.agregateur.relay.archive.parserFor
import app.agregateur.relay.archive.selectEntry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Archives réelles produites par WinRAR, 7-Zip et tar (voir `src/test/resources/archives`). */
class ArchiveTest {
    private val video = fixture("video.bin")

    @Test
    fun `rar5 stocke sur quatre volumes`() = checkVideo(ArchiveKind.RAR, "rar5.part1.rar", "rar5.part2.rar", "rar5.part3.rar", "rar5.part4.rar")

    @Test
    fun `rar4 stocke sur quatre volumes`() = checkVideo(ArchiveKind.RAR, "rar4.part1.rar", "rar4.part2.rar", "rar4.part3.rar", "rar4.part4.rar")

    @Test
    fun `zip stocke`() = checkVideo(ArchiveKind.ZIP, "stocke.zip")

    @Test
    fun `tar avec nom long pax`() {
        val volumes = volumesOf("archive.tar")
        val entries = parserFor(ArchiveKind.TAR).entries(volumes)
        assertEquals(listOf("lisezmoi.txt", "video.mkv"), entries.take(2).map { it.name })
        val long = entries[2]
        assertTrue(long.name.endsWith("episode.mkv") && long.name.length > 100, long.name)
        assertEquals(70_000, long.size)
        assertContentEquals(video.copyOf(70_000), ArchiveMediaSource(volumes, long).open(0).readBytes())
        checkVideo(ArchiveKind.TAR, "archive.tar", pattern = "/video\\.mkv$/i")
    }

    @Test
    fun `fichier choisi par index ou motif`() {
        val volumes = volumesOf("stocke.zip")
        val parser = parserFor(ArchiveKind.ZIP)
        val entries = parser.entries(volumes)
        assertEquals(3, entries.size, "le dossier lui-même n'est pas un fichier")
        val textIndex = entries.indexOfFirst { it.name == "lisezmoi.txt" }
        assertEquals("lisezmoi.txt", selectEntry(entries, textIndex, null).name)
        assertEquals("lisezmoi.txt", selectEntry(entries, null, "/\\.TXT$/i").name)
        assertEquals("video.mkv", selectEntry(entries, null, null).name)
        assertFailsWith<RelayException> { selectEntry(entries, 9, null) }
        assertFailsWith<RelayException> { selectEntry(entries, null, "/\\.avi$/") }
    }

    @Test
    fun `compresse ou chiffre refuse avec un message clair`() {
        val zip = parserFor(ArchiveKind.ZIP).entries(volumesOf("compresse.zip"))
        assertFalse(zip.single().stored)
        assertEquals(415, assertFailsWith<RelayException> { selectEntry(zip, null, null) }.status)

        val rar = parserFor(ArchiveKind.RAR).entries(volumesOf("rar5-compresse.rar"))
        assertEquals(415, assertFailsWith<RelayException> { selectEntry(rar, null, null) }.status)

        val encrypted = assertFailsWith<RelayException> { parserFor(ArchiveKind.RAR).entries(volumesOf("rar5-chiffre.rar")) }
        assertEquals(415, encrypted.status)
    }

    @Test
    fun `volume manquant ou mauvais format`() {
        val partial = parserFor(ArchiveKind.RAR).entries(volumesOf("rar5.part1.rar", "rar5.part2.rar"))
        assertFalse(partial.first { it.name == "video.mkv" }.stored)
        assertFailsWith<RelayException> { parserFor(ArchiveKind.RAR).entries(volumesOf("stocke.zip")) }
        assertFailsWith<RelayException> { parserFor(ArchiveKind.ZIP).entries(volumesOf("archive.tar")) }
    }

    private fun checkVideo(kind: ArchiveKind, vararg names: String, pattern: String? = null) {
        val volumes = volumesOf(*names)
        val parser = parserFor(kind)
        val entry = parser.locate(volumes, selectEntry(parser.entries(volumes), null, pattern))
        assertEquals("video.mkv", entry.name.substringAfterLast('/'))
        assertEquals(video.size.toLong(), entry.size)
        val source = ArchiveMediaSource(volumes, entry)
        assertContentEquals(video, source.open(0).readBytes())
        // Positions à cheval sur deux volumes et proches de la fin.
        listOf(1L, 40_000L, 40_960L, 81_000L, 149_999L).forEach { start ->
            val bytes = source.open(start).readNBytes(5_000)
            assertContentEquals(video.copyOfRange(start.toInt(), minOf(video.size, start.toInt() + 5_000)), bytes, "position $start")
        }
    }

    private fun volumesOf(vararg names: String): Volumes = BlockCache(FileVolumes(names.map(::fixture)), blockSize = 4096)

    private fun fixture(name: String): ByteArray = File(javaClass.getResource("/archives/$name")!!.toURI()).readBytes()
}

/** Volumes en mémoire, pour tester les formats sans réseau. */
class FileVolumes(private val data: List<ByteArray>) : Volumes {
    override val count: Int get() = data.size
    override fun size(volume: Int): Long = data[volume].size.toLong()

    override fun read(volume: Int, position: Long, length: Int): ByteArray {
        val bytes = data[volume]
        if (position >= bytes.size) return ByteArray(0)
        return bytes.copyOfRange(position.toInt(), minOf(bytes.size, position.toInt() + length))
    }

    override fun stream(volume: Int, position: Long, end: Long): InputStream =
        ByteArrayInputStream(data[volume], position.toInt(), (end - position).toInt())
}
