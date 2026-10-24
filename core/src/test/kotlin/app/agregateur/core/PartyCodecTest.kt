package app.agregateur.core

import app.agregateur.core.party.PartyAction
import app.agregateur.core.party.PartyCodec
import app.agregateur.core.party.PartyCodes
import app.agregateur.core.party.PartyEnvelope
import app.agregateur.core.party.PartyMedia
import app.agregateur.core.party.PartyMember
import app.agregateur.core.party.PartyMessage
import app.agregateur.core.party.PartyPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class PartyCodecTest {
    private val media = PartyMedia("series", "tt0903747", "tt0903747:1:2", "Breaking Bad", "https://img/p.jpg", "https://m/manifest.json")

    @Test
    fun `aller-retour de chaque message`() {
        listOf(
            PartyMessage.Command(3, PartyAction.SEEK, media, 120_000, true),
            PartyMessage.State(3, "u2", media, 121_000, false),
            PartyMessage.Hello,
            PartyMessage.Wait(true),
            PartyMessage.Ping(7),
            PartyMessage.Pong("u1", 7),
            PartyMessage.Chat("salut"),
            PartyMessage.Config(hostOnly = true),
        ).forEach { m ->
            val envelope = PartyEnvelope(from = "u1", m = m)
            assertEquals(envelope, PartyCodec.decode(PartyCodec.encode(envelope)))
        }
    }

    @Test
    fun `format sur le fil`() {
        val text = PartyCodec.encode(PartyEnvelope(from = "u1", m = PartyMessage.Wait(true))).toString()
        assertEquals("""{"v":1,"from":"u1","m":{"t":"wait","on":true}}""", text)
    }

    @Test
    fun `messages illisibles, inconnus ou hors limites ignores`() {
        fun decode(text: String) = PartyCodec.decode(Json.parseToJsonElement(text))
        assertNull(decode("""{"v":1,"from":"u1","m":{"t":"chat","text":""}}"""))
        assertNull(decode("""{"v":1,"from":"u1","m":{"t":"chat","text":"${"x".repeat(300)}"}}"""))
        assertNull(decode("""{"v":2,"from":"u1","m":{"t":"hello"}}"""))
        assertEquals("x", (decode("""{"v":1,"from":"u1","m":{"t":"chat","text":"x"}}""")!!.m as PartyMessage.Chat).text)
        assertNull(decode("""{"v":1,"from":"","m":{"t":"hello"}}"""))
        assertNull(decode("""{"v":1,"from":"u1","m":{"t":"cmd","version":1,"action":"PLAY","positionMs":-5,"playing":true,"media":{"type":"movie","id":"x","videoId":"x","title":"t"}}}"""))
        assertNull(decode("""{"v":1,"from":"u1","m":{"t":"cmd","version":1,"action":"PLAY","positionMs":5,"playing":true,"media":{"type":"movie","id":"","videoId":"x","title":"t"}}}"""))
        assertNull(decode("""{"v":1,"from":"u1","m":{"t":"cmd","version":1,"action":"DANCE","positionMs":5,"playing":true,"media":{"type":"movie","id":"x","videoId":"x","title":"t"}}}"""))
        assertNull(PartyCodec.decode(JsonPrimitive("hello")))
        assertEquals(PartyMessage.Hello, decode("""{"v":1,"from":"u1","m":{"t":"hello","extra":1},"more":true}""")!!.m)
    }

    @Test
    fun `presence`() {
        val json = PartyCodec.encodePresence(PartyPresence("alice", watching = true))
        assertEquals(PartyMember("u1", "alice", true), PartyCodec.decodePresence("u1", json))
        assertEquals(PartyMember("u2", "bob", false), PartyCodec.decodePresence("u2", Json.parseToJsonElement("""{"name":"bob","phx_ref":"F1"}""")))
        assertNull(PartyCodec.decodePresence("u3", Json.parseToJsonElement("""{"watching":true}""")))
    }

    @Test
    fun `codes de salle`() {
        assertEquals("ABCD2345", PartyCodes.normalize("abcd-2345"))
        assertEquals("ABCD2345", PartyCodes.normalize(" ABCD 2345 "))
        assertEquals("ABCD2345", PartyCodes.normalize("watchmate://party/ABCD2345"))
        assertNull(PartyCodes.normalize("ABCD234"))
        assertNull(PartyCodes.normalize("ABCD2I45"), "I exclu")
        assertNull(PartyCodes.normalize("ABCD2O45"), "O exclu")
        assertEquals("ABCD-2345", PartyCodes.display("ABCD2345"))
    }
}
