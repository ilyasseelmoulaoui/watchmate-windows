package app.agregateur.core

import app.agregateur.core.model.ProxyHeaders
import app.agregateur.core.model.Stream
import app.agregateur.core.model.StreamHints
import app.agregateur.core.playback.ArchiveKind
import app.agregateur.core.playback.RelayRequest
import app.agregateur.core.playback.RelayTarget
import app.agregateur.core.playback.RelayUrls
import app.agregateur.core.playback.Route
import app.agregateur.core.playback.SourceKind
import app.agregateur.core.playback.fileMatcher
import app.agregateur.core.playback.kind
import app.agregateur.core.playback.route
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRouterTest {
    @Test
    fun `sources sans relais`() {
        assertEquals(
            Route.PlayDirect("https://x/v.mp4", emptyMap()),
            route(Stream(url = "https://x/v.mp4")),
        )
        assertEquals(Route.PlayPlatform("abcdefghijk"), route(Stream(ytId = "abcdefghijk")))
        assertEquals(Route.OpenExternalLink("https://x"), route(Stream(externalUrl = "https://x")))
    }

    @Test
    fun `en tetes de requete transmis au lecteur`() {
        val stream = Stream(
            url = "https://x/v.mp4",
            behaviorHints = StreamHints(proxyHeaders = ProxyHeaders(request = mapOf("Referer" to "https://x"))),
        )
        assertEquals(
            Route.PlayDirect("https://x/v.mp4", mapOf("Referer" to "https://x")),
            route(stream),
        )
    }

    @Test
    fun `http distant refuse, http local accepte`() {
        assertEquals(Route.Insecure("http://exemple.org/v.mp4"), route(Stream(url = "http://exemple.org/v.mp4")))
        assertEquals(
            Route.PlayDirect("http://127.0.0.1:11/v.mp4", emptyMap()),
            route(Stream(url = "http://127.0.0.1:11/v.mp4")),
        )
    }

    @Test
    fun `schemas dangereux ou invalides jamais lus ni ouverts`() {
        listOf("file:///data/x", "content://x/y", "intent://x#Intent;end", "javascript:alert(1)", "n importe quoi").forEach {
            assertEquals(Route.Unplayable(SourceKind.DIRECT), route(Stream(url = it)), it)
            assertEquals(Route.Unplayable(SourceKind.EXTERNAL_LINK), route(Stream(externalUrl = it)), it)
        }
        assertEquals(Route.Unplayable(SourceKind.PLATFORM_VIDEO), route(Stream(ytId = "../etc?x=1")))
    }

    @Test
    fun `r5 nzb, inconnu et torrent invalide restent illisibles`() {
        assertEquals(Route.Unplayable(SourceKind.TORRENT), route(Stream(infoHash = "h")))
        assertEquals(Route.Unplayable(SourceKind.NZB), route(Stream(nzbUrl = "n")))
        assertEquals(Route.Unplayable(SourceKind.UNKNOWN), route(Stream()))
        assertEquals(SourceKind.TORRENT, Stream(infoHash = "h").kind())
    }

    @Test
    fun `r5 torrent par le relais, trackers retenus et nettoyes`() {
        val stream = Stream(
            infoHash = HASH.uppercase(),
            fileIdx = 2,
            sources = listOf("tracker:udp://t.example:80/announce", "dht:$HASH", "tracker:javascript:x", "udp://sans-prefixe"),
        )
        assertEquals(
            Route.PlayViaRelay(RelayTarget.Torrent(HASH, 2, listOf("udp://t.example:80/announce"))),
            route(stream),
        )
    }

    @Test
    fun `r5 archives par le relais selon le format`() {
        val rar = Stream(
            rarUrls = listOf(JsonPrimitive("https://x/a.part1.rar"), buildJsonObject { put("url", "https://x/a.part2.rar"); put("bytes", 10) }),
            fileMustInclude = "/\\.mkv$/i",
        )
        assertEquals(
            Route.PlayViaRelay(RelayTarget.Archive(ArchiveKind.RAR, listOf("https://x/a.part1.rar", "https://x/a.part2.rar"), null, "/\\.mkv$/i")),
            route(rar),
        )
        val tar = Stream(tarUrls = listOf(JsonPrimitive("https://x/a.tar")), fileIdx = 1)
        assertEquals(Route.PlayViaRelay(RelayTarget.Archive(ArchiveKind.TAR, listOf("https://x/a.tar"), 1, null)), route(tar))
    }

    @Test
    fun `r5 archives refusees`() {
        val refused = listOf(
            Stream(sevenZipUrls = listOf(JsonPrimitive("https://x/a.7z"))),
            Stream(tgzUrls = listOf(JsonPrimitive("https://x/a.tgz"))),
            Stream(rarUrls = listOf(JsonPrimitive("http://exemple.org/a.rar"))),
            Stream(rarUrls = listOf(JsonPrimitive("file:///sdcard/a.rar"))),
            Stream(zipUrls = listOf(JsonPrimitive("https://x/a.z01"), JsonPrimitive("https://x/a.zip"))),
            Stream(rarUrls = emptyList()),
        )
        refused.forEach { assertEquals(Route.Unplayable(SourceKind.ARCHIVE), route(it), it.toString()) }
        val badPattern = route(Stream(tarUrls = listOf(JsonPrimitive("https://x/a.tar")), fileMustInclude = "/(/"))
        assertEquals(Route.PlayViaRelay(RelayTarget.Archive(ArchiveKind.TAR, listOf("https://x/a.tar"), null, null)), badPattern)
    }

    @Test
    fun `adresse du relais relue a l identique, secret verifie`() {
        val targets = listOf(
            RelayTarget.Torrent(HASH, null, listOf("udp://t.example:80/announce", "https://t.example/announce?k=a&b")),
            RelayTarget.Torrent(HASH, 0, emptyList()),
            RelayTarget.Archive(ArchiveKind.RAR, listOf("https://x/a b.rar", "https://x/a.r00?s=1&t=2"), 3, "/\\.(mkv|mp4)$/i"),
            RelayTarget.Archive(ArchiveKind.ZIP, listOf("https://x/a.zip"), null, null),
        )
        targets.forEach { target ->
            val url = RelayUrls.play(4321, TOKEN, target).toHttpUrl()
            assertEquals("127.0.0.1", url.host)
            assertEquals(RelayRequest.Play(target), RelayUrls.parse(url, TOKEN))
            assertNull(RelayUrls.parse(url, TOKEN.replace('a', 'b')))
        }
        assertEquals(
            RelayRequest.TorrentStatus(HASH),
            RelayUrls.parse(RelayUrls.torrentStatus(4321, TOKEN, HASH).toHttpUrl(), TOKEN),
        )
        listOf("/$TOKEN/torrent/zz/auto", "/$TOKEN/torrent/$HASH/-1", "/$TOKEN/archive/7z/auto?u=https://x", "/$TOKEN/x", "/$TOKEN")
            .forEach { assertNull(RelayUrls.parse("http://127.0.0.1:1$it".toHttpUrl(), TOKEN), it) }
    }

    @Test
    fun `motif de fichier a la javascript`() {
        val regex = fileMatcher("/\\.mkv$|\\.mp4$/i")!!
        assertTrue(regex.containsMatchIn("Film.MKV"))
        assertFalse(regex.containsMatchIn("film.nfo"))
        assertTrue(fileMatcher("sample")!!.containsMatchIn("x.sample.mkv"))
        assertNull(fileMatcher("/(/"))
    }

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef01234567"
        const val TOKEN = "a1a2a3a4"
    }
}
