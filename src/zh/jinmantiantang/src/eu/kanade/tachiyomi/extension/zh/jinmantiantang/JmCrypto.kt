package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object JmCrypto {
    private const val APP_TOKEN_SECRET = "18comicAPP"
    private const val APP_DATA_SECRET = "185Hcomic3PAPP7R"
    private const val APP_VERSION = "2.0.21"

    fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun generateTokenParam(ts: Long): String = "$ts,$APP_VERSION"

    fun generateToken(ts: Long): String = md5Hex("$ts$APP_TOKEN_SECRET")

    fun decryptData(data: String, ts: Long): String {
        val key = md5Hex("$ts$APP_DATA_SECRET").toByteArray(Charsets.UTF_8)
        val encrypted = java.util.Base64.getDecoder().decode(data)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }

    fun encryptForTest(data: String, ts: Long): String {
        val key = md5Hex("$ts$APP_DATA_SECRET").toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(encrypted)
    }
}
