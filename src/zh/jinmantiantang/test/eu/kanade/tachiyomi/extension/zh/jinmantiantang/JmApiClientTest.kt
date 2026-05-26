package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class JmApiClientTest {
    @Test
    fun testParseAlbumDetail_SingleChapter() {
        val json = JSONObject("""
            {
                "id": 123,
                "name": "Test Album",
                "author": ["Author1"],
                "tags": ["tag1", "tag2"],
                "description": "Desc",
                "series": [],
                "series_id": "0"
            }
        """.trimIndent())

        val detail = JmApiClient.parseAlbumDetailRaw(json)
        assertEquals(123, detail.id)
        assertEquals("Test Album", detail.title)
        assertEquals("Author1", detail.author)
        assertEquals("tag1, tag2", detail.genre)
        assertEquals("Desc", detail.description)
    }

    @Test
    fun testParseChapterList_SingleChapterFallback() {
        val json = JSONObject("""
            {
                "id": 123,
                "name": "Test Album",
                "series": [],
                "series_id": "0"
            }
        """.trimIndent())

        val chapters = JmApiClient.parseChapterListRaw(json)
        assertEquals(1, chapters.size)
        assertEquals(123, chapters[0].id)
    }
}
