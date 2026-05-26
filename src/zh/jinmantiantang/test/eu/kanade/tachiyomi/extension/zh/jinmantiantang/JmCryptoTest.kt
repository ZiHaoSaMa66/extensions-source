package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import org.junit.Assert.assertEquals
import org.junit.Test

class JmCryptoTest {
    @Test
    fun testMd5Hex() {
        val result = JmCrypto.md5Hex("170056680518comicAPP")
        assertEquals("81498a20feea7fbb7149c637e49702e3", result)
    }

    @Test
    fun testGenerateTokenParam() {
        assertEquals("1700566805,2.0.21", JmCrypto.generateTokenParam(1700566805))
    }

    @Test
    fun testDecryptData() {
        val ts = 1700566805L
        val original = "{\"code\":200}"
        val encrypted = JmCrypto.encryptForTest(original, ts)
        val decrypted = JmCrypto.decryptData(encrypted, ts)
        assertEquals(original, decrypted)
    }
}
