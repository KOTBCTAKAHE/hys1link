package io.github.kotbctakahe.hys1link.core

import android.util.Log
import io.github.kotbctakahe.hys1link.crypto.ProfileCipher
import io.github.kotbctakahe.hys1link.crypto.AttachmentCipher
import io.github.kotbctakahe.hys1link.crypto.B64
import io.github.kotbctakahe.hys1link.store.AttachmentInfo
import org.json.JSONObject
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Fetches and decrypts profiles (name, about, avatar) using the contact's profile key. */
class Profiles(private val core: Core) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun refreshAsync(uuid: String) {
        if (!inFlight.add(uuid)) return
        core.scope.launchIo {
            try { refresh(uuid) } catch (e: Exception) { Log.w(TAG, "profile $uuid failed", e) } finally { inFlight.remove(uuid) }
        }
    }

    fun refresh(uuid: String) {
        val contact = core.repo.contact(uuid) ?: return
        val pk = contact.profileKey ?: return
        val version = ProfileKey(pk).getProfileKeyVersion(UUID.fromString(uuid)).serialize()
        val p = core.api.versionedProfile(uuid, version)
        val name = ProfileCipher.decryptName(pk, p.optString("name").takeIf { it.isNotBlank() && it != "null" })
        val about = ProfileCipher.decryptString(pk, p.optString("about").takeIf { it.isNotBlank() && it != "null" })
        val fullName = name?.let { (g, f) -> listOf(g, f).filter { it.isNotBlank() }.joinToString(" ") }
        var avatarFile: String? = null
        p.optString("avatar").takeIf { it.isNotBlank() && it != "null" }?.let { path ->
            runCatching {
                val enc = core.api.downloadProfileAvatar(path)
                val plain = ProfileCipher.decrypt(pk, enc)
                val f = File(core.context.filesDir, "avatars/${uuid}.jpg").apply { parentFile?.mkdirs() }
                f.writeBytes(plain)
                avatarFile = f.absolutePath
            }.onFailure { Log.w(TAG, "avatar $uuid failed", it) }
        }
        core.repo.upsertContact(uuid, profileName = fullName, about = about, avatarPath = avatarFile)
    }

    /** Refresh own profile, for display in settings. */
    fun refreshSelf() {
        val self = core.account.aci ?: return
        val pk = core.account.profileKey ?: return
        core.repo.upsertContact(self, e164 = core.account.e164, profileKey = pk)
        refresh(self)
    }

    companion object { private const val TAG = "Profiles" }
}

/** Downloads and decrypts attachments into app storage. */
class Attachments(private val core: Core) {
    private val maxAuto = 25L * 1024 * 1024

    fun autoDownload(messageId: Long, list: List<AttachmentInfo>) {
        list.forEachIndexed { i, a ->
            val small = (a.size ?: 0) <= maxAuto
            val media = a.contentType?.startsWith("image/") == true || a.contentType?.startsWith("audio/") == true || a.fileName == "sticker.webp"
            if (small && media) core.scope.launchIo { runCatching { download(messageId, i, a) }.onFailure { Log.w("Attachments", "dl failed", it) } }
        }
    }

    fun downloadPreview(messageId: Long, img: io.github.kotbctakahe.hys1link.proto.AttachmentPointer) {
        val idOrKey = img.cdnKey ?: img.cdnId?.toString() ?: return
        val plain = AttachmentCipher.decrypt(core.api.downloadCdn(img.cdnNumber, idOrKey, 5L shl 20), img.key ?: return, img.digest)
        val f = File(core.context.filesDir, "attachments/${messageId}_preview.jpg").apply { parentFile?.mkdirs() }
        f.writeBytes(if (img.size != null && img.size in 1 until plain.size) plain.copyOf(img.size) else plain)
        core.repo.setPreviewImage(messageId, f.absolutePath)
    }

    fun download(messageId: Long, index: Int, a: AttachmentInfo): String {
        val ptr = JSONObject(a.pointer ?: error("no pointer"))
        val idOrKey = ptr.optString("key").takeIf { it.isNotBlank() && it != "null" } ?: ptr.getString("id")
        val enc = core.api.downloadCdn(ptr.optInt("cdn", 0), idOrKey)
        val key = B64.dec(ptr.getString("k"))
        val digest = ptr.optString("d").takeIf { it.isNotBlank() && it != "null" }?.let(B64::dec)
        val plain = AttachmentCipher.decrypt(enc, key, digest).let { if (a.size != null && a.size in 1 until it.size) it.copyOf(a.size) else it }
        val ext = when {
            a.contentType == "image/png" -> "png"; a.contentType == "image/webp" -> "webp"; a.contentType == "image/gif" -> "gif"
            a.contentType?.startsWith("image/") == true -> "jpg"
            a.contentType?.startsWith("audio/") == true -> "m4a"
            a.contentType?.startsWith("video/") == true -> "mp4"
            else -> a.fileName?.substringAfterLast('.', "bin") ?: "bin"
        }
        val f = File(core.context.filesDir, "attachments/${messageId}_$index.$ext").apply { parentFile?.mkdirs() }
        f.writeBytes(plain)
        core.repo.updateAttachmentPath(messageId, index, f.absolutePath)
        return f.absolutePath
    }
}
