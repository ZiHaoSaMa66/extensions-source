package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class JmImageScramblerTest {
    @Test
    fun testGetSegmentNum_NoScramble() {
        // aid < scrambleId → no scrambling
        assertEquals(0, JmImageScrambler.getSegmentNum(220980, 100000, "00001"))
    }

    @Test
    fun testGetSegmentNum_Fixed10() {
        // aid >= scrambleId && aid < 268850 → fixed 10 segments
        assertEquals(10, JmImageScrambler.getSegmentNum(220980, 250000, "00001"))
    }

    @Test
    fun testGetSegmentNum_Dynamic10() {
        // aid >= 268850 && aid < 421926 → modulus 10
        val num = JmImageScrambler.getSegmentNum(220980, 300000, "00001")
        assert(num in listOf(2, 4, 6, 8, 10, 12, 14, 16, 18, 20))
        assertEquals(num % 2, 0) // always even
        assert(num >= 2 && num <= 20)
    }

    @Test
    fun testGetSegmentNum_Dynamic8() {
        // aid >= 421926 → modulus 8
        val num = JmImageScrambler.getSegmentNum(220980, 500000, "00001")
        assert(num in listOf(2, 4, 6, 8, 10, 12, 14, 16))
        assertEquals(num % 2, 0) // always even
        assert(num >= 2 && num <= 16)
    }

    @Test
    fun testGetSegmentNum_ConsistentWithWebScrambler() {
        // Verify that JmImageScrambler produces the same result as ScrambledImageInterceptor's algorithm
        // ScrambledImageInterceptor uses: md5LastCharCode(aid.toString() + imgIndex) where imgIndex has no extension
        val aid = 500000
        val imgIndex = "00047"

        // ScrambledImageInterceptor's md5LastCharCode logic
        val md5Digest = MessageDigest.getInstance("MD5")
        val lastByte = md5Digest.digest("$aid$imgIndex".toByteArray()).last().toInt() and 0xFF
        val webCharCode = lastByte.toString(16).last().code
        val webRows = 2 * (webCharCode % 8) + 2

        // JmImageScrambler's logic (filename without extension)
        val apiNum = JmImageScrambler.getSegmentNum(220980, aid, imgIndex)

        assertEquals(webRows, apiNum)
    }

    @Test
    fun testGetSegmentNum_MultipleFiles() {
        // Test with various filenames to ensure consistency
        val testCases = listOf("00001", "00010", "00047", "00100")
        val aid = 450000 // >= 421926, uses modulus 8

        for (filename in testCases) {
            val num = JmImageScrambler.getSegmentNum(220980, aid, filename)
            assert(num >= 2 && num <= 16) { "Expected 2-16 for aid=$aid, filename=$filename, got $num" }
            assertEquals(0, num % 2) // always even
        }
    }
}
