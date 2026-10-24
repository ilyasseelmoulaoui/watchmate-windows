package app.agregateur.relay

import app.agregateur.relay.http.RangeSpec
import app.agregateur.relay.http.parseRange
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpRangeTest {
    @Test
    fun `plages courantes des lecteurs`() {
        assertEquals(RangeSpec.Full, parseRange(null, 100))
        assertEquals(RangeSpec.Partial(0, 99), parseRange("bytes=0-", 100))
        assertEquals(RangeSpec.Partial(10, 19), parseRange("bytes=10-19", 100))
        assertEquals(RangeSpec.Partial(90, 99), parseRange("bytes=90-500", 100))
        assertEquals(RangeSpec.Partial(80, 99), parseRange("bytes=-20", 100))
        assertEquals(RangeSpec.Partial(0, 99), parseRange("bytes=-500", 100))
    }

    @Test
    fun `plages hors fichier ou mal formees`() {
        assertEquals(RangeSpec.Unsatisfiable, parseRange("bytes=100-", 100))
        assertEquals(RangeSpec.Unsatisfiable, parseRange("bytes=-0", 100))
        assertEquals(RangeSpec.Full, parseRange("bytes=0-1,5-6", 100))
        assertEquals(RangeSpec.Full, parseRange("bytes=20-10", 100))
        assertEquals(RangeSpec.Full, parseRange("items=0-1", 100))
        assertEquals(RangeSpec.Full, parseRange("bytes=a-b", 100))
    }
}
