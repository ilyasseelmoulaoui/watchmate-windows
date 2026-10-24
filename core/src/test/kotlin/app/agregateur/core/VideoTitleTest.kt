package app.agregateur.core

import app.agregateur.core.addon.AddonJson
import app.agregateur.core.model.Meta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoTitleTest {
    @Test
    fun `titre d episode par title ou name, null tolere`() {
        val meta = AddonJson.decodeFromString<Meta>(
            """{"id":"tt1","type":"series","name":"S","genres":null,"videos":[
                {"id":"tt1:1:1","name":"Pilot","season":1,"episode":1},
                {"id":"tt1:1:2","title":"Deux","name":"ignoré","season":1,"episode":2},
                {"id":"tt1:1:3","title":"","season":1,"episode":3}]}""",
        )
        assertEquals(listOf("Pilot", "Deux", null), meta.videos.map { it.displayTitle })
        assertEquals(emptyList(), meta.genres)
        assertNull(meta.videos[2].displayTitle)
    }
}
