package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubanFilmsTest {
    @Test fun parsesMoviesAndTvAndUsesReachablePosterShard() {
        val movies = """
            {"subjects":[
              {"title":"黑球","rate":"8.6","episodes_info":"","type":"movie",
               "cover":"https://img9.doubanio.com/view/photo/s_ratio_poster/public/p1.jpg"},
              {"title":"无评分电影","rate":"","type":"movie",
               "cover":"https://img3.doubanio.com/view/photo/s_ratio_poster/public/p2.jpg"}
            ]}
        """.trimIndent()
        val tv = """
            {"subjects":[
              {"title":"小城良方","rate":"","episodes_info":"更新至22集","type":"tv",
               "cover":"https://img2.doubanio.com/view/photo/s_ratio_poster/public/p3.jpg"}
            ]}
        """.trimIndent()

        val items = DoubanFilms.interleave(movies, tv)

        assertEquals(3, items.size)
        assertEquals("黑球", items[0].title)
        assertEquals("电影 · 豆瓣 8.6", items[0].meta)
        assertEquals("https://img2.doubanio.com/view/photo/l_ratio_poster/public/p1.jpg", items[0].image)
        assertEquals("小城良方", items[1].title)
        assertEquals("剧集 · 更新至22集", items[1].meta)
        assertEquals("无评分电影", items[2].title)
        assertTrue(items.all { it.image.startsWith("https://img2.doubanio.com/") })
    }

    @Test fun customDoubanJsonIsAcceptedAsAFeed() {
        val parsed = ShelfJson.parse(
            """{"subjects":[{"title":"测试剧","rate":7.2,"episodes_info":"更新至3集","type":"tv","cover":{"url":"https://img1.doubanio.com/view/photo/m_ratio_poster/public/p4.jpg"}}]}""",
        )!!
        assertEquals("测试剧", parsed.single().title)
        assertEquals("剧集 · 豆瓣 7.2 · 更新至3集", parsed.single().meta)
        assertEquals("https://img2.doubanio.com/view/photo/l_ratio_poster/public/p4.jpg", parsed.single().image)
    }
    @Test fun extractsIdsForLandscapeDetailLookup() {
        val entries = DoubanFilms.entries(
            """{"subjects":[{"id":"37215318","title":"黑球","rate":"8.6","episodes_info":"","type":"movie"}]}""",
            "电影",
        )
        assertEquals(1, entries.size)
        assertEquals("37215318", entries.single().id)
        assertEquals("黑球", entries.single().title)
        assertEquals("电影 · 豆瓣 8.6", entries.single().meta)
    }

}
