package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class EsportexFootballTest {
    @Test fun usesFullLandscapePosterAndDecodedCompetition() {
        val body = """
            {"success":true,"football":[
              {"slug":"lens-at-lille","tag":"Lens vs Lille","kickoff":"2026-10-31 03:05","endTime":"2026-10-31 07:05",
               "poster":"https://static.ppvservices.st/assets/thumb/abc-thumbnail.jpg",
               "iframes":[{"url":"https://streams.esportex.site/player#c2MvZXZlbnQtZXNwbi1sZWFndWUtc29jY2VyLWZyYS0xLTQwMTg3NjQyMA=="}],
               "league":"Football"},
              {"slug":"other","tag":"Other A vs Other B","kickoff":"2026-10-31 08:00","endTime":"2026-10-31 10:00",
               "poster":"https://static.ppvservices.st/assets/thumb/def-thumbnail.jpg","league":"Liga MX"}
            ]}
        """.trimIndent()
        val now = LocalDateTime.of(2026, 10, 31, 5, 0)

        val items = EsportexFootball.items(body, now)

        assertEquals(2, items.size)
        assertEquals("Lens vs Lille", items[0].title)
        assertEquals("法甲 · 直播中", items[0].meta)
        assertEquals("https://static.ppvservices.st/assets/thumb/abc.jpg", items[0].image)
        assertEquals("墨超 · 未开始 · 08:00", items[1].meta)
    }
}
