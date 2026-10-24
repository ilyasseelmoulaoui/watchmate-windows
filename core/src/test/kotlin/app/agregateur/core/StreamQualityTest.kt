package app.agregateur.core

import app.agregateur.core.model.Stream
import app.agregateur.core.model.StreamHints
import app.agregateur.core.playback.DecoderCaps
import app.agregateur.core.playback.fits
import app.agregateur.core.playback.qualityPenalty
import app.agregateur.core.playback.traits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamQualityTest {
    private val emulator = DecoderCaps(maxAvcHeight = 2160, maxHevcHeight = 2160, hevc10Bit = false)
    private val noHevc = DecoderCaps(maxAvcHeight = 1080, maxHevcHeight = 0, hevc10Bit = false)

    private fun s(name: String? = null, title: String? = null, file: String? = null) =
        Stream(infoHash = "a".repeat(40), name = name, title = title, behaviorHints = StreamHints(filename = file))

    @Test
    fun `lit la resolution et le codec dans le nom ou le fichier`() {
        val t = s(name = "Module\n4K", title = "Film.2024.2160p.WEB-DL.DV.HDR.HEVC.10bit").traits()
        assertEquals(2160, t.height)
        assertTrue(t.hevc)
        assertTrue(t.tenBit)

        val f = s(file = "Film.2024.1080p.x264.mkv").traits()
        assertEquals(1080, f.height)
        assertFalse(f.hevc)
        assertFalse(f.tenBit)

        assertEquals(2160, s(name = "UHD").traits().height)
        assertEquals(null, s(name = "Source").traits().height)
    }

    @Test
    fun `ne confond pas des nombres avec une resolution`() {
        val t = s(title = "Blade.Runner.2049.x2650.mkv").traits()
        assertEquals(null, t.height)
        assertFalse(t.hevc)
    }

    @Test
    fun `une source HEVC 10 bits ne convient pas a un decodeur 8 bits`() {
        val hdr = s(title = "Film.2160p.HEVC.10bit").traits()
        assertFalse(hdr.fits(emulator))
        assertTrue(s(title = "Film.1080p.HEVC").traits().fits(emulator))
        assertTrue(s(title = "Film.1080p.x264").traits().fits(emulator))
    }

    @Test
    fun `sans decodeur HEVC on garde H264 et les sources muettes`() {
        assertFalse(s(title = "Film.1080p.x265").traits().fits(noHevc))
        assertFalse(s(title = "Film.HEVC").traits().fits(noHevc))
        assertFalse(s(title = "Film.2160p.x264").traits().fits(noHevc))
        assertTrue(s(title = "Film.720p.x264").traits().fits(noHevc))
        assertTrue(s(name = "Source").traits().fits(noHevc))
    }

    @Test
    fun `1080p est prefere a la 4K et a une source sans resolution`() {
        val p1080 = s(title = "1080p").traits().qualityPenalty()
        val p720 = s(title = "720p").traits().qualityPenalty()
        val p2160 = s(title = "2160p").traits().qualityPenalty()
        val unknown = s(name = "Source").traits().qualityPenalty()
        assertTrue(p1080 < p720)
        assertTrue(p720 < p2160)
        assertTrue(p720 < unknown)
    }
}
