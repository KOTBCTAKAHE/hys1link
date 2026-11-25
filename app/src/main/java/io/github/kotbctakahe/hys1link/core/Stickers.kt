package io.github.kotbctakahe.hys1link.core

import io.github.kotbctakahe.hys1link.social.mediaUrl
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class StickerItem(val id: Int, val url: String, val emoji: String?, val type: String?, val packName: String?)
data class StickerPack(val id: Int, val title: String, val cover: String?, val stickers: List<StickerItem>)

/**
 * Link "sticker v2" messages (StickerV2Parser / StickerV2Encryptor in Link 7.4.4).
 * The message body is "/link=" + {"fn": "sticker", "data": {"pn": pack, "u": enc(url), "s": id, "t": type, "e": emoji}}.
 * enc(url) = base64(iv || AES-128-CBC(url)), key = SHA-256(timestamp with last 3 digits = "000")[0..16],
 * timestamp = the message's sent timestamp.
 */
object LinkStickers {
    const val PREFIX = "/link="

    fun isLinkFunction(body: String?) = body != null && body.startsWith(PREFIX)

    private fun key(ts: Long): SecretKeySpec {
        val s = ts.toString()
        val k = if (s.length >= 3) s.substring(0, s.length - 3) + "000" else "000"
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(k.toByteArray(Charsets.UTF_8)).copyOf(16), "AES")
    }

    fun encryptUrl(url: String, ts: Long): String {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, key(ts), IvParameterSpec(iv))
        return android.util.Base64.encodeToString(iv + c.doFinal(url.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
    }

    fun decryptUrl(enc: String, ts: Long): String? = runCatching {
        val b = android.util.Base64.decode(enc, android.util.Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, key(ts), IvParameterSpec(b.copyOfRange(0, 16)))
        String(c.doFinal(b, 16, b.size - 16), Charsets.UTF_8)
    }.getOrNull()

    /** Same literal layout as Link's buildStickerJson, so Link clients parse it identically. */
    fun build(item: StickerItem, ts: Long): String = buildString {
        append(PREFIX)
        append("{\"fn\": \"sticker\", \"data\": {")
        append("\"pn\":\"").append(item.packName.orEmpty()).append("\", ")
        append("\"u\": \"").append(encryptUrl(item.url, ts)).append("\", ")
        append("\"s\": \"").append(item.id).append("\", ")
        append("\"t\": \"").append(item.type.orEmpty()).append("\", ")
        append("\"e\": \"").append(item.emoji.orEmpty()).append("\"}}")
    }

    private fun field(json: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"\\s*((?:\\\\.|[^\"\\\\])*)\"").find(json)?.groupValues?.get(1)?.trim()
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")?.replace("\\n", "\n")

    data class Parsed(val url: String?, val emoji: String?, val packName: String?, val id: String?)

    /** Tries the sent timestamp first, then ±1s (sender builds the JSON just before stamping the message). */
    fun parse(body: String, ts: Long): Parsed? {
        if (!isLinkFunction(body)) return null
        val json = body.removePrefix(PREFIX)
        if (field(json, "fn") != "sticker") return null
        val enc = field(json, "u")
        val url = enc?.let { e -> listOf(ts, ts - 1000, ts + 1000).firstNotNullOfOrNull { decryptUrl(e, it) } }
        return Parsed(mediaUrl(url), field(json, "e"), field(json, "pn"), field(json, "s"))
    }
}
