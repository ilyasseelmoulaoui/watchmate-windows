package app.agregateur.core

import app.agregateur.core.party.MAX_VOICE_PEERS
import app.agregateur.core.party.PartyCodec
import app.agregateur.core.party.PartyMember
import app.agregateur.core.party.PartyPresence
import app.agregateur.core.party.VoiceCodec
import app.agregateur.core.party.VoiceEnvelope
import app.agregateur.core.party.VoiceMesh
import app.agregateur.core.party.VoiceSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class PartyVoiceTest {
    @Test
    fun `aller-retour des signaux pour le destinataire`() {
        listOf(
            VoiceSignal.Offer("b", "v=0 offer"),
            VoiceSignal.Answer("b", "v=0 answer"),
            VoiceSignal.Ice("b", "0", 0, "candidate:1 1 udp 2122260223 192.168.1.2 50000 typ host"),
            VoiceSignal.Ice("b", null, 1, "candidate:2"),
        ).forEach { s ->
            val envelope = VoiceEnvelope(from = "a", s = s)
            assertEquals(envelope, VoiceCodec.decodeFor("b", VoiceCodec.encode(envelope)))
        }
    }

    @Test
    fun `ignore les signaux d'autrui, les siens et les illisibles`() {
        val offer = VoiceCodec.encode(VoiceEnvelope(from = "a", s = VoiceSignal.Offer("b", "sdp")))
        assertNull(VoiceCodec.decodeFor("c", offer))
        assertNull(VoiceCodec.decodeFor("a", VoiceCodec.encode(VoiceEnvelope(from = "a", s = VoiceSignal.Offer("a", "sdp")))))
        assertNull(VoiceCodec.decodeFor("b", Json.parseToJsonElement("""{"v":1,"from":"a","s":{"t":"nope","to":"b"}}""")))
        assertNull(VoiceCodec.decodeFor("b", VoiceCodec.encode(VoiceEnvelope(v = 99, from = "a", s = VoiceSignal.Offer("b", "sdp")))))
        assertNull(VoiceCodec.decodeFor("b", VoiceCodec.encode(VoiceEnvelope(from = "a", s = VoiceSignal.Offer("b", "x".repeat(40_000))))))
        assertNull(VoiceCodec.decodeFor("b", VoiceCodec.encode(VoiceEnvelope(from = "a", s = VoiceSignal.Ice("b", "0", -1, "c")))))
    }

    @Test
    fun `seul le plus petit identifiant offre`() {
        assertTrue(VoiceMesh.isOfferer("a", "b"))
        assertFalse(VoiceMesh.isOfferer("b", "a"))
        assertEquals(VoiceMesh.Plan(setOf("c"), emptySet()), VoiceMesh.plan("b", true, listOf("a", "b", "c"), emptySet()))
    }

    @Test
    fun `raccroche ceux qui quittent le vocal et tout en sortant`() {
        assertEquals(VoiceMesh.Plan(emptySet(), setOf("c")), VoiceMesh.plan("b", true, listOf("a", "b"), setOf("a", "c")))
        assertEquals(VoiceMesh.Plan(emptySet(), setOf("a", "c")), VoiceMesh.plan("b", false, listOf("a", "b", "c"), setOf("a", "c")))
    }

    @Test
    fun `attend avant de retenter un pair en echec et plafonne le maillage`() {
        assertEquals(emptySet(), VoiceMesh.plan("a", true, listOf("a", "b"), emptySet(), cooling = setOf("b")).open)
        val many = (1..9).map { "m$it" }
        assertEquals(MAX_VOICE_PEERS, VoiceMesh.plan("a", true, many, emptySet()).open.size)
    }

    @Test
    fun `presence vocale et compatibilite avec l'ancien format`() {
        val encoded = PartyCodec.encodePresence(PartyPresence("Lea", watching = true, voice = true, muted = true))
        assertEquals(PartyMember("u/1", "Lea", true, voice = true, muted = true), PartyCodec.decodePresence("u/1", encoded))
        val old = Json.parseToJsonElement("""{"name":"Sam","watching":false}""")
        assertEquals(PartyMember("u/2", "Sam", false), PartyCodec.decodePresence("u/2", old))
    }
}
