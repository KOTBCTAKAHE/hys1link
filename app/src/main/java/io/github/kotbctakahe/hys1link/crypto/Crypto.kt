package io.github.kotbctakahe.hys1link.crypto

import io.github.kotbctakahe.hys1link.proto.ProtoMessage
import io.github.kotbctakahe.hys1link.proto.ProtoWriter
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object B64 {
    fun enc(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    fun encNoPad(b: ByteArray): String = java.util.Base64.getEncoder().withoutPadding().encodeToString(b)
    fun dec(s: String): ByteArray {
        val t = s.trim()
        return if (t.contains('-') || t.contains('_')) java.util.Base64.getUrlDecoder().decode(t)
        else java.util.Base64.getDecoder().decode(t)
    }
}

fun hmacSha256(key: ByteArray, vararg data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    data.forEach { mac.update(it) }
    return mac.doFinal()
}

fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

/** HKDF (RFC 5869, "v3" as used by Signal) with zero salt. */
object Hkdf {
    fun derive(ikm: ByteArray, info: ByteArray, length: Int, salt: ByteArray = ByteArray(32)): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmacSha256(prk, t, info, byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }
}

/** Decrypts the ProvisionEnvelope sent by the primary device (same scheme as Link's PrimaryProvisioningCipher). */
class ProvisioningCipher(val keyPair: ECKeyPair = Curve.generateKeyPair()) {
    fun decrypt(envelopePublicKey: ByteArray, body: ByteArray): ByteArray {
        require(body.isNotEmpty() && body[0].toInt() == 1) { "Unsupported provisioning version" }
        val iv = body.copyOfRange(1, 17)
        val mac = body.copyOfRange(body.size - 32, body.size)
        val ciphertext = body.copyOfRange(17, body.size - 32)
        val shared = Curve.calculateAgreement(ECPublicKey(envelopePublicKey), keyPair.privateKey)
        val keys = Hkdf.derive(shared, "TextSecure Provisioning Message".toByteArray(), 64)
        val expected = hmacSha256(keys.copyOfRange(32, 64), body.copyOfRange(0, body.size - 32))
        require(MessageDigest.isEqual(expected, mac)) { "Provisioning MAC mismatch" }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.copyOfRange(0, 32), "AES"), IvParameterSpec(iv))
        return c.doFinal(ciphertext)
    }
}

/** Device name encryption, identical to Link's DeviceNameCipher. */
object DeviceNameCipher {
    fun encrypt(name: String, identityKey: IdentityKey): String {
        val plaintext = name.toByteArray(Charsets.UTF_8)
        val ephemeral = Curve.generateKeyPair()
        val master = Curve.calculateAgreement(identityKey.publicKey, ephemeral.privateKey)
        val syntheticIv = hmacSha256(hmacSha256(master, "auth".toByteArray()), plaintext).copyOf(16)
        val cipherKey = hmacSha256(hmacSha256(master, "cipher".toByteArray()), syntheticIv)
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(ByteArray(16)))
        val ct = c.doFinal(plaintext)
        val proto = ProtoWriter().bytes(1, ephemeral.publicKey.serialize()).bytes(2, syntheticIv).bytes(3, ct).toByteArray()
        return B64.enc(proto)
    }
}

/** Signal attachment encryption: key = aes(32)||mac(32), blob = iv||AES-CBC||HMAC. */
object AttachmentCipher {
    class Encrypted(val blob: ByteArray, val key: ByteArray, val digest: ByteArray, val plaintextLength: Int)

    /** Signal's PaddingInputStream.getPaddedSize: max(541, 1.05^ceil(log_1.05(size))). */
    fun paddedSize(size: Long): Long = maxOf(541.0, Math.floor(Math.pow(1.05, Math.ceil(Math.log(size.toDouble()) / Math.log(1.05))))).toLong()

    fun encrypt(plain: ByteArray): Encrypted {
        val key = randomBytes(64)
        val iv = randomBytes(16)
        val padded = plain.copyOf(paddedSize(plain.size.toLong()).toInt().coerceAtLeast(plain.size))
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOfRange(0, 32), "AES"), IvParameterSpec(iv))
        val ct = c.doFinal(padded)
        val body = iv + ct
        val mac = hmacSha256(key.copyOfRange(32, 64), body)
        val blob = body + mac
        return Encrypted(blob, key, MessageDigest.getInstance("SHA-256").digest(blob), plain.size)
    }

    fun decrypt(blob: ByteArray, key: ByteArray, digest: ByteArray?): ByteArray {
        require(key.size == 64) { "Bad attachment key" }
        require(blob.size > 48) { "Attachment too short" }
        if (digest != null && digest.isNotEmpty()) {
            val d = MessageDigest.getInstance("SHA-256").digest(blob)
            require(MessageDigest.isEqual(d, digest)) { "Attachment digest mismatch" }
        }
        val mac = blob.copyOfRange(blob.size - 32, blob.size)
        val expected = hmacSha256(key.copyOfRange(32, 64), blob.copyOfRange(0, blob.size - 32))
        require(MessageDigest.isEqual(mac, expected)) { "Attachment MAC mismatch" }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOfRange(0, 32), "AES"), IvParameterSpec(blob.copyOfRange(0, 16)))
        return c.doFinal(blob, 16, blob.size - 48)
    }
}

/** Profile field/avatar decryption: AES-256-GCM, nonce(12)||ciphertext||tag. */
object ProfileCipher {
    fun decrypt(profileKey: ByteArray, input: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(profileKey, "AES"), GCMParameterSpec(128, input, 0, 12))
        return c.doFinal(input, 12, input.size - 12)
    }

    /** Returns (given, family). Names are zero padded and separated by \u0000. */
    fun decryptName(profileKey: ByteArray, b64: String?): Pair<String, String>? {
        if (b64.isNullOrBlank()) return null
        return runCatching {
            val plain = decrypt(profileKey, B64.dec(b64))
            var end = plain.size
            while (end > 0 && plain[end - 1].toInt() == 0) end--
            val s = String(plain, 0, end, Charsets.UTF_8)
            val parts = s.split('\u0000')
            parts[0] to parts.getOrElse(1) { "" }
        }.getOrNull()
    }

    fun decryptString(profileKey: ByteArray, b64: String?): String? {
        if (b64.isNullOrBlank()) return null
        return runCatching {
            val plain = decrypt(profileKey, B64.dec(b64))
            var end = plain.size
            while (end > 0 && plain[end - 1].toInt() == 0) end--
            String(plain, 0, end, Charsets.UTF_8)
        }.getOrNull()
    }

    fun unidentifiedAccessKey(profileKey: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(profileKey, "AES"), GCMParameterSpec(128, ByteArray(12)))
        return c.doFinal(ByteArray(16)).copyOf(16)
    }
}

/** Content padding used by Signal: content || 0x80 || 0x00..., to a multiple of 160 bytes. */
object Padding {
    fun pad(content: ByteArray): ByteArray {
        val len = content.size + 1
        val padded = ((len + 159) / 160) * 160
        val out = ByteArray(padded)
        System.arraycopy(content, 0, out, 0, content.size)
        out[content.size] = 0x80.toByte()
        return out
    }

    fun unpad(padded: ByteArray): ByteArray {
        var i = padded.size - 1
        while (i >= 0) {
            when (padded[i].toInt() and 0xFF) {
                0x80 -> return padded.copyOf(i)
                0x00 -> i--
                else -> return padded
            }
        }
        return padded
    }
}

fun ProtoMessage.Companion.safeParse(b: ByteArray?): ProtoMessage? = b?.let { runCatching { ProtoMessage.parse(it) }.getOrNull() }
