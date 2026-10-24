package app.agregateur.core

import app.agregateur.core.party.PartyAction
import app.agregateur.core.party.PartyCodec
import app.agregateur.core.party.PartyEnvelope
import app.agregateur.core.party.PartyMedia
import app.agregateur.core.party.PartyMember
import app.agregateur.core.party.PartyMessage
import app.agregateur.core.party.MAX_OFFSET_MS
import app.agregateur.core.party.PartyChatLine
import app.agregateur.core.party.PartyNotice
import app.agregateur.core.party.PartyPlayer
import app.agregateur.core.party.PartySync
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val MOVIE = PartyMedia("movie", "tt0012349", "tt0012349", "The Kid", moduleUrl = "https://m.example/manifest.json")
private val OTHER = PartyMedia("movie", "tt0000002", "tt0000002", "Other")

/** Lecteur simulé : il avance avec l'horloge, et comme ExoPlayer il signale aussi les changements qu'on lui impose. */
private class FakePlayer(val rate: Double = 1.0) : PartyPlayer {
    lateinit var sync: PartySync
    var pos = 0.0
    var rateSet = 1f
    var buffering = false
    var seeks = 0

    /** Temps de chargement après un saut (une source lente, comme un torrent). */
    var seekCostMs = 0L
    var loadLeftMs = 0L
    private var bufferingToReport = false
    var playing = false
    override val playWhenReady: Boolean get() = playing
    override val positionMs: Long get() = pos.toLong()

    override fun setPlayWhenReady(play: Boolean) {
        playing = play
        sync.onLocalPlayWhenReady(play)
    }

    override fun seekTo(positionMs: Long) {
        pos = positionMs.toDouble()
        seeks++
        sync.onLocalSeek(positionMs)
        if (seekCostMs > 0) {
            loadLeftMs = seekCostMs
            bufferingToReport = true
        }
    }

    override fun setSpeed(speed: Float) {
        rateSet = speed
    }

    fun advance(ms: Long) {
        // Comme ExoPlayer, l'état « chargement » est signalé après l'appel qui l'a causé.
        if (bufferingToReport) {
            bufferingToReport = false
            startBuffering()
        }
        if (loadLeftMs > 0) {
            loadLeftMs -= ms
            if (loadLeftMs <= 0) ready()
            return
        }
        if (playing && !buffering) pos += ms * rateSet * rate
    }

    fun userPause() = setPlayWhenReady(false)
    fun userPlay() = setPlayWhenReady(true)
    fun userSeek(ms: Long) = seekTo(ms)

    fun startBuffering() {
        buffering = true
        sync.onLocalBuffering(true)
    }

    fun ready() {
        buffering = false
        sync.onLocalBuffering(false)
    }
}

private class Peer(val net: Net, val id: String) {
    val sent = mutableListOf<PartyMessage>()
    val notices = mutableListOf<PartyNotice>()
    val chats = mutableListOf<PartyChatLine>()
    val sync = PartySync(id, { net.now }, { net.send(this, it) }, { notices += it }, { chats += it })
    var player: FakePlayer? = null

    /** Ouvre [media] à [startMs] : le lecteur est créé en lecture, puis signale qu'il est prêt. */
    fun watch(media: PartyMedia, startMs: Long = 0, rate: Double = 1.0, loadMs: Long = 0, seekCostMs: Long = 0): FakePlayer {
        // Le lecteur démarre en lecture avant d'être rattaché à la salle : pas encore d'écho possible.
        val p = FakePlayer(rate).also { it.sync = sync; it.pos = startMs.toDouble(); it.playing = true }
        player = p
        p.seekCostMs = seekCostMs
        p.loadLeftMs = loadMs
        sync.attach(p, media)
        if (loadMs == 0L) p.ready() else p.startBuffering()
        return p
    }
}

private class Net(private val defaultLatencyMs: Long = 80) {
    var now = 1_000_000L
    val peers = mutableListOf<Peer>()
    val latencies = mutableMapOf<Pair<String, String>, Long>()
    private var seq = 0L
    private val queue = PriorityQueue<Delivery>(compareBy<Delivery>({ it.at }, { it.seq }))

    private class Delivery(val at: Long, val seq: Long, val to: Peer, val payload: kotlinx.serialization.json.JsonElement)

    fun join(id: String, hostId: String = "a"): Peer = Peer(this, id).also {
        it.sync.hostId = hostId
        peers += it
        updatePresence()
        it.sync.onJoined()
    }

    fun leave(peer: Peer) {
        peers -= peer
        updatePresence()
    }

    fun send(from: Peer, message: PartyMessage) {
        from.sent += message
        val payload = PartyCodec.encode(PartyEnvelope(from = from.id, m = message))
        peers.filter { it !== from }.forEach { to ->
            val latency = latencies[from.id to to.id] ?: latencies[to.id to from.id] ?: defaultLatencyMs
            queue += Delivery(now + latency, seq++, to, payload)
        }
    }

    fun updatePresence() {
        val members = peers.map { PartyMember(it.id, it.id, it.sync.synced) }
        peers.forEach { it.sync.onMembers(members) }
    }

    fun advance(ms: Long, step: Long = 50) {
        var left = ms
        while (left > 0) {
            now += step
            left -= step
            while (queue.isNotEmpty() && queue.peek().at <= now) {
                val d = queue.poll()
                if (d.to !in peers) continue
                val envelope = PartyCodec.decode(d.payload)!!
                d.to.sync.onMessage(envelope.from, envelope.m)
            }
            peers.forEach { it.player?.advance(step) }
            updatePresence()
            peers.forEach { it.sync.tick() }
        }
    }
}

class PartySyncTest {
    private fun assertClose(expected: Long, actual: Long, tolerance: Long, message: String = "") =
        assertTrue(abs(expected - actual) <= tolerance, "$message attendu ≈ $expected, obtenu $actual (±$tolerance)")

    @Test
    fun `un membre qui arrive rejoint la position de la salle et la suit`() {
        val net = Net()
        val a = net.join("a")
        val pa = a.watch(MOVIE, startMs = 60_000)
        net.advance(3_000)
        val b = net.join("b")
        net.advance(500)
        assertEquals(MOVIE, b.sync.timeline!!.media)
        val pb = b.watch(MOVIE, startMs = 0)
        assertClose(pa.positionMs, pb.positionMs, 300, "position à l'arrivée")
        net.advance(20_000)
        assertTrue(pb.playWhenReady)
        assertClose(pa.positionMs, pb.positionMs, 300)
    }

    @Test
    fun `pause, lecture et saut passent a tout le monde, sans echo`() {
        val net = Net()
        val a = net.join("a")
        val pa = a.watch(MOVIE, 10_000)
        val b = net.join("b")
        net.advance(500)
        val pb = b.watch(MOVIE)
        val c = net.join("c")
        net.advance(500)
        val pc = c.watch(MOVIE)
        net.advance(2_000)

        val before = net.peers.sumOf { p -> p.sent.count { it is PartyMessage.Command } }
        pb.userPause()
        net.advance(500)
        assertFalse(pa.playWhenReady)
        assertFalse(pc.playWhenReady)
        assertClose(pb.positionMs, pa.positionMs, 200)
        assertClose(pb.positionMs, pc.positionMs, 200)
        net.advance(3_000)
        assertEquals(before + 1, net.peers.sumOf { p -> p.sent.count { it is PartyMessage.Command } }, "une seule commande")
        assertEquals(PartyAction.PAUSE, a.notices.last().action)

        pc.userPlay()
        net.advance(500)
        assertTrue(pa.playWhenReady && pb.playWhenReady)

        pa.userSeek(600_000)
        net.advance(500)
        assertClose(pa.positionMs, pb.positionMs, 300)
        assertClose(pa.positionMs, pc.positionMs, 300)
        net.advance(5_000)
        assertEquals(before + 3, net.peers.sumOf { p -> p.sent.count { it is PartyMessage.Command } }, "ni écho ni correction renvoyés")
    }

    @Test
    fun `un lecteur plus lent est rattrape`() {
        val net = Net()
        val a = net.join("a")
        val pa = a.watch(MOVIE, 0)
        val b = net.join("b")
        net.advance(500)
        val pb = b.watch(MOVIE, rate = 0.97)
        var worst = 0L
        repeat(120) {
            net.advance(1_000)
            if (it > 10) worst = maxOf(worst, abs(pa.positionMs - pb.positionMs))
        }
        assertTrue(worst < 1_000, "écart maximal $worst ms")
        assertClose(pa.positionMs, pb.positionMs, 500)
    }

    @Test
    fun `deux decisions simultanees aboutissent au meme etat partout`() {
        val net = Net(defaultLatencyMs = 150)
        val a = net.join("a")
        val pa = a.watch(MOVIE, 0)
        val b = net.join("b")
        net.advance(500)
        val pb = b.watch(MOVIE)
        net.advance(2_000)
        pa.userPause()
        pb.userSeek(300_000)
        net.advance(1_000)
        val ta = a.sync.timeline!!
        val tb = b.sync.timeline!!
        assertEquals(ta.version to ta.by, tb.version to tb.by)
        assertEquals(ta.playing, tb.playing)
        assertEquals(pa.playWhenReady, pb.playWhenReady)
        net.advance(6_000)
        assertClose(pa.positionMs, pb.positionMs, 500)
    }

    @Test
    fun `les autres attendent celui qui charge, puis repartent ensemble`() {
        val net = Net()
        val a = net.join("a")
        val pa = a.watch(MOVIE, 0)
        val b = net.join("b")
        net.advance(500)
        val pb = b.watch(MOVIE)
        net.advance(3_000)
        pb.startBuffering()
        net.advance(4_000)
        assertFalse(pa.playWhenReady, "A attend B")
        val frozenAt = pa.positionMs
        net.advance(2_000)
        assertEquals(frozenAt, pa.positionMs)
        pb.ready()
        net.advance(300)
        assertTrue(pa.playWhenReady && pb.playWhenReady)
        net.advance(6_000)
        assertClose(pa.positionMs, pb.positionMs, 400)
        assertEquals(1, a.sent.count { it is PartyMessage.Command }, "l'attente ne crée pas de commande")
    }

    @Test
    fun `le chargement initial d un membre fige les autres deja prets`() {
        val net = Net()
        val a = net.join("a")
        val pa = a.watch(MOVIE, 0)
        val b = net.join("b")
        net.advance(500)
        val pb = b.watch(MOVIE, loadMs = 10_000)
        net.advance(4_000)
        assertFalse(pa.playWhenReady, "A attend le chargement initial de B")
        assertTrue(a.sync.waitingFor.contains("b"))
        net.advance(8_000)
        assertTrue(pa.playWhenReady && pb.playWhenReady && !pb.buffering)
        assertClose(pa.positionMs, pb.positionMs, 500)
    }

    @Test
    fun `l'attente est limitee puis la salle repart`() {
        val net = Net()
        val a = net.join("a")
        val pa = a.watch(MOVIE, 0)
        val b = net.join("b")
        net.advance(500)
        val pb = b.watch(MOVIE)
        net.advance(2_000)
        pb.startBuffering()
        net.advance(4_000)
        assertFalse(pa.playWhenReady)
        net.advance(10_000)
        assertTrue(pa.playWhenReady, "A repart sans B")
    }

    @Test
    fun `celui qui part ne bloque plus la salle`() {
        val net = Net()
        val a = net.join("a")
        val pa = a.watch(MOVIE, 0)
        val b = net.join("b")
        net.advance(500)
        val pb = b.watch(MOVIE)
        net.advance(2_000)
        pb.startBuffering()
        net.advance(4_000)
        assertFalse(pa.playWhenReady)
        net.leave(b)
        net.advance(100)
        assertTrue(pa.playWhenReady)
    }

    @Test
    fun `lancer autre chose change la salle et detache ceux qui regardent l'ancien titre`() {
        val net = Net()
        val a = net.join("a")
        val pa = a.watch(MOVIE, 0)
        val b = net.join("b")
        net.advance(500)
        b.watch(MOVIE)
        net.advance(1_000)
        b.watch(OTHER, 0)
        net.advance(500)
        assertEquals(OTHER, a.sync.timeline!!.media)
        assertEquals(PartyAction.LOAD, a.notices.last().action)
        assertFalse(a.sync.synced)
        val commands = a.sent.count { it is PartyMessage.Command }
        pa.userPause()
        net.advance(500)
        assertEquals(commands, a.sent.count { it is PartyMessage.Command }, "A ne commande plus la salle")
        assertEquals(0L, a.sync.startPositionFor(MOVIE) ?: 0L)
        assertTrue(a.sync.startPositionFor(OTHER)!! > 0)
    }

    @Test
    fun `le retard du reseau est mesure et compense`() {
        val net = Net(defaultLatencyMs = 400)
        val a = net.join("a")
        val pa = a.watch(MOVIE, 0)
        val b = net.join("b")
        net.advance(2_000)
        assertClose(400, b.sync.latencyTo("a"), 60)
        val pb = b.watch(MOVIE)
        net.advance(2_000)
        pa.userSeek(100_000)
        net.advance(1_000)
        assertClose(pa.positionMs, pb.positionMs, 150, "saut compensé du retard")
    }

    @Test
    fun `une source lente qui rejoint la salle finit par la rattraper sans boucler`() {
        val net = Net()
        val a = net.join("a")
        val pa = a.watch(MOVIE, 800_000)
        net.advance(2_000)
        val b = net.join("b")
        net.advance(500)
        val pb = b.watch(MOVIE, loadMs = 8_000, seekCostMs = 3_000)
        net.advance(60_000)
        assertTrue(pb.playWhenReady && !pb.buffering, "B lit")
        assertTrue(pa.playWhenReady, "A lit")
        assertClose(pa.positionMs, pb.positionMs, 500)
        assertTrue(pb.seeks <= 4, "${pb.seeks} sauts")
        val seeks = pb.seeks
        net.advance(60_000)
        assertEquals(seeks, pb.seeks, "plus aucun saut une fois rattrapé")
        assertClose(pa.positionMs, pb.positionMs, 500)
    }

    @Test
    fun `une source qui ne charge jamais ne bloque pas la salle en boucle`() {
        val net = Net()
        val a = net.join("a")
        val pa = a.watch(MOVIE, 0)
        val b = net.join("b")
        net.advance(500)
        val pb = b.watch(MOVIE)
        net.advance(2_000)
        pb.startBuffering()
        var pausedMs = 0L
        repeat(120) {
            net.advance(500)
            if (!pa.playWhenReady) pausedMs += 500
        }
        assertTrue(pausedMs <= 12_000, "A en pause $pausedMs ms sur 60 s")
    }

    @Test
    fun `deux appareils du meme compte se synchronisent`() {
        val net = Net()
        val phone = net.join("u1/phone", hostId = "u1")
        val tv = net.join("u1/tv", hostId = "u1")
        val pPhone = phone.watch(MOVIE, 30_000)
        net.advance(500)
        val pTv = tv.watch(MOVIE)
        net.advance(1_000)
        assertClose(pPhone.positionMs, pTv.positionMs, 300)
        pTv.userPause()
        net.advance(500)
        assertFalse(pPhone.playWhenReady)
        assertEquals("u1/phone", phone.sync.leaderId(), "un appareil de l'hôte mène")
    }

    @Test
    fun `le meneur est l'hote s'il regarde, sinon le plus petit identifiant qui regarde`() {
        val net = Net()
        val b = net.join("b", hostId = "c")
        val a = net.join("a", hostId = "c")
        val c = net.join("c", hostId = "c")
        net.advance(100)
        assertEquals(null, a.sync.leaderId())
        b.watch(MOVIE)
        net.advance(500)
        assertEquals("b", a.sync.leaderId())
        c.watch(MOVIE)
        net.advance(500)
        assertEquals("c", a.sync.leaderId())
        assertTrue(c.sync.isLeader())
    }

    @Test
    fun `mode hote seul, un invite ne pilote pas la salle`() {
        val net = Net()
        val host = net.join("host/1", hostId = "host")
        val guest = net.join("guest/1", hostId = "host")
        val ph = host.watch(MOVIE, 10_000)
        net.advance(500)
        host.sync.setHostOnly(true)
        net.advance(200)
        assertTrue(guest.sync.hostOnly)
        guest.watch(MOVIE, 10_000)
        net.advance(300)
        guest.player!!.userPause()
        net.advance(500)
        assertTrue(ph.playWhenReady, "la pause de l'invité est ignorée")
        assertTrue(guest.player!!.playWhenReady, "le lecteur invité est remis en lecture")
    }

    @Test
    fun `decalage manuel local sans changer la position de la salle`() {
        val net = Net()
        val a = net.join("a")
        val b = net.join("b")
        val pa = a.watch(MOVIE, 20_000)
        net.advance(500)
        val pb = b.watch(MOVIE)
        net.advance(500)
        b.sync.setOffsetMs(5_000)
        net.advance(200)
        assertClose(pa.positionMs + 5_000, pb.positionMs, 400)
        b.sync.setOffsetMs(MAX_OFFSET_MS + 1)
        assertEquals(MAX_OFFSET_MS, b.sync.offsetMs)
    }

    @Test
    fun `chat diffuse a tous les membres`() {
        val net = Net()
        val a = net.join("a")
        val b = net.join("b")
        assertTrue(a.sync.sendChat("  bonjour  "))
        net.advance(200)
        assertEquals(1, a.chats.size)
        assertEquals("bonjour", a.chats[0].text)
        assertEquals(1, b.chats.size)
        assertEquals("bonjour", b.chats[0].text)
        assertEquals("a", b.chats[0].from)
        assertFalse(a.sync.sendChat("   "))
    }

    @Test
    fun `un lecteur en retard qui charge ne fige pas la salle`() {
        val net = Net()
        val a = net.join("a")
        val pa = a.watch(MOVIE, 0)
        val b = net.join("b")
        net.advance(500)
        val pb = b.watch(MOVIE)
        net.advance(5_000)
        // B est artificiellement loin derrière et charge (rattrapage), sans commander la salle.
        pb.pos = (pa.positionMs - 12_000).coerceAtLeast(0).toDouble()
        pb.startBuffering()
        val ahead = pa.positionMs
        net.advance(5_000)
        assertTrue(pa.playWhenReady, "A continue pendant que B rattrape")
        assertTrue(pa.positionMs > ahead, "A a avancé")
    }

    @Test
    fun `pause locale annule l attente en cours`() {
        val net = Net()
        val a = net.join("a")
        val pa = a.watch(MOVIE, 0)
        val b = net.join("b")
        net.advance(500)
        val pb = b.watch(MOVIE)
        net.advance(2_000)
        pb.startBuffering()
        net.advance(4_000)
        assertFalse(pa.playWhenReady)
        pb.userPause()
        net.advance(500)
        assertFalse(pa.playWhenReady)
        assertFalse(pb.playWhenReady)
        assertTrue(a.sync.waitingFor.isEmpty(), "plus personne en attente")
        assertEquals(false, a.sync.timeline!!.playing)
    }

    @Test
    fun `apres une attente resolue, pas de nouvelle attente immediate`() {
        val net = Net()
        val a = net.join("a")
        val pa = a.watch(MOVIE, 0)
        val b = net.join("b")
        net.advance(500)
        val pb = b.watch(MOVIE)
        net.advance(2_000)
        pb.startBuffering()
        net.advance(4_000)
        assertFalse(pa.playWhenReady)
        pb.ready()
        net.advance(500)
        assertTrue(pa.playWhenReady)
        pb.startBuffering()
        net.advance(5_000)
        assertTrue(pa.playWhenReady, "cooldown : B ne refait pas attendre tout de suite")
    }
}
