package app.agregateur.core

import app.agregateur.core.addon.AddonClient
import app.agregateur.core.addon.AddonError
import app.agregateur.core.addon.AddonJson
import app.agregateur.core.addon.AddonResult
import app.agregateur.core.model.Manifest
import app.agregateur.core.model.Meta
import app.agregateur.core.model.Stream
import app.agregateur.core.model.StreamHints
import app.agregateur.core.model.Subtitle
import app.agregateur.core.modules.InstalledModule
import app.agregateur.core.modules.Origin
import app.agregateur.core.title.MetaOutcome
import app.agregateur.core.playback.SubtitleFormat
import app.agregateur.core.playback.subtitleFormatOf
import app.agregateur.core.title.TitleService
import app.agregateur.core.title.seasons
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class TitleServiceTest {
    private val routes = mutableMapOf<String, MockResponse>()
    private val requested = mutableListOf<String>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request.path.orEmpty()
                return routes[request.path] ?: MockResponse().setResponseCode(404)
            }
        }
        start()
    }
    private val service = TitleService(AddonClient())

    @AfterTest
    fun stop() = server.shutdown()

    private fun module(name: String, resources: String = """["meta","stream"]""", types: String = """["movie","series"]""") =
        InstalledModule(
            "http://127.0.0.1:${server.port}/$name/manifest.json",
            AddonJson.decodeFromString<Manifest>(
                """{"id":"$name","name":"$name","version":"1","types":$types,"resources":$resources,"catalogs":[]}""",
            ),
            Origin.ADDRESS,
            0,
        )

    private fun json(body: String) = MockResponse().setBody(body)

    @Test
    fun `la fiche vient d abord du module d origine`() = runBlocking<Unit> {
        routes["/a/meta/movie/tt1.json"] = json("""{"meta":{"id":"tt1","type":"movie","name":"Depuis A"}}""")
        routes["/b/meta/movie/tt1.json"] = json("""{"meta":{"id":"tt1","type":"movie","name":"Depuis B"}}""")
        val outcome = service.meta(listOf(module("a"), module("b")), "movie", "tt1", preferredModuleUrl = module("b").manifestUrl)
        val loaded = assertIs<MetaOutcome.Loaded>(outcome)
        assertEquals("Depuis B", loaded.meta.name)
        assertEquals("b", loaded.moduleName)
        assertEquals(listOf("/b/meta/movie/tt1.json"), requested)
    }

    @Test
    fun `repli sur un autre module si le premier n a pas de fiche`() = runBlocking<Unit> {
        routes["/a/meta/movie/tt1.json"] = json("""{"meta":{}}""")
        routes["/b/meta/movie/tt1.json"] = json("""{"meta":{"id":"tt1","type":"movie","name":"Depuis B"}}""")
        val outcome = service.meta(listOf(module("a"), module("b")), "movie", "tt1", preferredModuleUrl = module("a").manifestUrl)
        assertEquals("b", assertIs<MetaOutcome.Loaded>(outcome).moduleName)
    }

    @Test
    fun `fiche introuvable ou en erreur`() = runBlocking<Unit> {
        routes["/a/meta/movie/tt1.json"] = json("""{"meta":{}}""")
        assertEquals(MetaOutcome.NotFound, service.meta(listOf(module("a")), "movie", "tt1"))
        assertEquals(MetaOutcome.NotFound, service.meta(emptyList(), "movie", "tt1"))
        assertEquals(
            MetaOutcome.Failed(AddonError.HttpStatus(404)),
            service.meta(listOf(module("b")), "movie", "tt1"),
        )
    }

    @Test
    fun `un module sans la ressource meta n est pas interroge`() = runBlocking<Unit> {
        service.meta(listOf(module("a", resources = """["stream"]""")), "movie", "tt1")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sources des modules qui les declarent, une erreur n arrete pas les autres`() = runBlocking<Unit> {
        routes["/a/stream/movie/tt1.json"] = json("""{"streams":[{"url":"https://x/v.mp4"}]}""")
        // /b renvoie 404, /c ne déclare pas les types
        val modules = listOf(module("a"), module("b"), module("c", types = """["series"]"""))
        val results = service.streams(modules, "movie", "tt1").toList().associateBy { it.moduleName }
        assertEquals(setOf("a", "b"), results.keys)
        assertEquals(1, (results.getValue("a").result as AddonResult.Success).value.size)
        assertIs<AddonResult.Failure>(results.getValue("b").result)
    }

    @Test
    fun `saisons triees, specials puis sans saison a la fin, episodes dans l ordre`() {
        val meta = AddonJson.decodeFromString<Meta>(
            """{"id":"s","type":"series","name":"S","videos":[
                {"id":"v-x"},
                {"id":"v2-1","season":2,"episode":1},
                {"id":"v0-1","season":0,"episode":1},
                {"id":"v1-2","season":1,"episode":2},
                {"id":"v1-1","season":1,"episode":1}
            ]}""",
        )
        val seasons = meta.seasons()
        assertEquals(listOf(1, 2, 0, null), seasons.map { it.number })
        assertEquals(listOf("v1-1", "v1-2"), seasons[0].episodes.map { it.id })
    }

    private val subModuleResources = """["subtitles"]"""

    @Test
    fun `sous-titres de la source et des modules, doublons et adresses non chiffrees retires`() = runBlocking<Unit> {
        routes["/a/subtitles/movie/tt1/filename=f.mkv&videoSize=42.json"] = json(
            """{"subtitles":[{"id":"1","url":"https://s/fr.srt","lang":"fra"},{"id":"2","url":"http://s/en.srt","lang":"eng"},{"id":"3","url":"https://s/dup.vtt","lang":"eng"}]}""",
        )
        val stream = Stream(
            url = "https://x/v.mp4",
            subtitles = listOf(Subtitle("9", "https://s/dup.vtt", "eng"), Subtitle("8", "file:///x.srt", "eng")),
            behaviorHints = StreamHints(filename = "f.mkv", videoSize = 42),
        )
        val subs = service.subtitles(listOf(module("a", resources = subModuleResources)), "movie", "tt1", stream)
        assertEquals(listOf("https://s/dup.vtt", "https://s/fr.srt"), subs.map { it.url })
    }

    @Test
    fun `un module de sous-titres lent ne bloque pas la lecture`() = runBlocking<Unit> {
        routes["/a/subtitles/movie/tt1.json"] = json("""{"subtitles":[{"id":"1","url":"https://s/fr.srt","lang":"fra"}]}""")
            .setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)
        val stream = Stream(url = "https://x/v.mp4", subtitles = listOf(Subtitle("9", "https://s/own.srt", "eng")))
        val subs = service.subtitles(listOf(module("a", resources = subModuleResources)), "movie", "tt1", stream, timeoutMs = 300)
        assertEquals(listOf("https://s/own.srt"), subs.map { it.url })
    }

    @Test
    fun `un module de sous-titres en erreur est ignore`() = runBlocking<Unit> {
        assertEquals(emptyList(), service.subtitles(listOf(module("a", resources = subModuleResources)), "movie", "tt1"))
    }

    @Test
    fun `format de sous-titres deduit de l extension`() {
        assertEquals(SubtitleFormat.WEBVTT, subtitleFormatOf("https://s/a.VTT?x=1"))
        assertEquals(SubtitleFormat.SSA, subtitleFormatOf("https://s/a.ass"))
        assertEquals(SubtitleFormat.TTML, subtitleFormatOf("https://s/a.ttml"))
        assertEquals(SubtitleFormat.SUBRIP, subtitleFormatOf("https://s/a.srt"))
        assertEquals(SubtitleFormat.SUBRIP, subtitleFormatOf("https://s/telecharger?id=3"))
    }
}
