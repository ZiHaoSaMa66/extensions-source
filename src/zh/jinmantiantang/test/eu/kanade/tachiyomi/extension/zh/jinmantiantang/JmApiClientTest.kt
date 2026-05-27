package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class JmApiClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testParseAlbumDetail_SingleChapter() {
        val jsonStr = """
            {
                "id": 123,
                "name": "Test Album",
                "author": ["Author1"],
                "tags": ["tag1", "tag2"],
                "description": "Desc",
                "series": [],
                "series_id": "0"
            }
        """.trimIndent()

        val dto = json.decodeFromString<AlbumDto>(jsonStr)
        assertEquals(123, dto.id)
        assertEquals("Test Album", dto.name)
        assertEquals("Author1", dto.author.joinToString(", "))
        assertEquals("tag1, tag2", dto.tags.joinToString(", "))
        assertEquals("Desc", dto.description)
    }

    @Test
    fun testParseChapterList_SingleChapterFallback() {
        val jsonStr = """
            {
                "id": 123,
                "name": "Test Album",
                "series": [],
                "series_id": "0"
            }
        """.trimIndent()

        val dto = json.decodeFromString<AlbumDto>(jsonStr)
        val chapters = JmApiClient.parseChapterList(dto)
        assertEquals(1, chapters.size)
        assertEquals("/photo/123", chapters[0].url)
    }
}
