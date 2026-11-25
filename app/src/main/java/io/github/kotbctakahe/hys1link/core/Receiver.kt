package io.github.kotbctakahe.hys1link.core

import android.util.Log
import io.github.kotbctakahe.hys1link.LinkConfig
import io.github.kotbctakahe.hys1link.crypto.AttachmentCipher
import io.github.kotbctakahe.hys1link.crypto.B64
import io.github.kotbctakahe.hys1link.crypto.Padding
import io.github.kotbctakahe.hys1link.proto.AttachmentPointer
import io.github.kotbctakahe.hys1link.proto.Content
import io.github.kotbctakahe.hys1link.proto.ContactDetails
import io.github.kotbctakahe.hys1link.proto.DataMessage
import io.github.kotbctakahe.hys1link.proto.Envelope
import io.github.kotbctakahe.hys1link.proto.EnvelopeType
import io.github.kotbctakahe.hys1link.proto.ProtoMessage
import io.github.kotbctakahe.hys1link.proto.ProtoReader
import io.github.kotbctakahe.hys1link.proto.ReceiptType
import io.github.kotbctakahe.hys1link.proto.WsRequest
import io.github.kotbctakahe.hys1link.store.AttachmentInfo
import io.github.kotbctakahe.hys1link.store.Conversation
import io.github.kotbctakahe.hys1link.store.MsgStatus
import org.json.JSONObject
import org.signal.libsignal.metadata.SealedSessionCipher
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.util.Hex
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import java.util.UUID

/** Decrypts and applies incoming envelopes. */
class Receiver(private val core: Core) {
    private val validator by lazy { CertificateValidator(ECPublicKey(B64.dec(LinkConfig.UNIDENTIFIED_SENDER_TRUST_ROOT))) }

    /** Typing indicators: conversation id -> expiry time. */
    val typing = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Returns HTTP-like status for the websocket ack. */
    fun handle(req: WsRequest): Int {
        if (req.verb == "PUT" && req.path == "/api/v1/queue/empty") { core.onQueueEmpty(); return 200 }
        if (req.verb != "PUT" || req.path != "/api/v1/message") return 200
        val body = req.body ?: return 200
        val env = runCatching { Envelope.decode(body) }.getOrElse { Log.w(TAG, "bad envelope", it); return 200 }
        try {
            process(env)
        } catch (e: Exception) {
            // Never NACK undecryptable messages forever: log and drop.
            Log.w(TAG, "failed to process envelope type=${env.type} from=${env.sourceUuid}.${env.sourceDevice}", e)
        }
        return 200
    }

    private fun process(env: Envelope) {
        val store = core.store ?: return
        val content = env.content ?: return
        val acct = core.account
        if (env.destinationUuid != null && acct.pni != null && env.destinationUuid.equals(acct.pni, true)) {
            Log.i(TAG, "skipping PNI-addressed envelope"); return
        }

        var sender = env.sourceUuid
        var senderDevice = env.sourceDevice
        val plaintext: ByteArray = when (env.type) {
            EnvelopeType.CIPHERTEXT ->
                SessionCipher(store, SignalProtocolAddress(sender!!, senderDevice)).decrypt(SignalMessage(content))
            EnvelopeType.PREKEY_BUNDLE -> {
                val p = SessionCipher(store, SignalProtocolAddress(sender!!, senderDevice)).decrypt(PreKeySignalMessage(content))
                core.scope.launchIo { runCatching { core.keys.refreshPreKeys() }.onFailure { Log.w(TAG, "prekey refresh failed", it) } }
                p
            }
            EnvelopeType.UNIDENTIFIED_SENDER -> {
                val cipher = SealedSessionCipher(store, UUID.fromString(acct.aci), acct.e164, acct.deviceId)
                val r = cipher.decrypt(validator, content, env.serverTimestamp)
                sender = r.senderUuid
                senderDevice = r.deviceId
                r.paddedMessage
            }
            EnvelopeType.LINK_DEVICE_REQUEST -> {
                // Link's server-triggered headless device link. Only the primary acts on it; a
                // secondary device must never do so. We just record that it happened.
                Log.w(TAG, "Received LINK_DEVICE_REQUEST from ${env.sourceUuid} — ignored")
                core.repo.db.putLong("vds_link_requests", core.repo.db.getLong("vds_link_requests") + 1)
                return
            }
            else -> return
        }
        val from = sender ?: return
        handleContent(from, senderDevice, env, ProtoMessage.parse(Padding.unpad(plaintext)))
    }

    private fun handleContent(sender: String, senderDevice: Int, env: Envelope, c: ProtoMessage) {
        val self = core.account.aci
        c.bytes(Content.SKDM)?.let { b ->
            runCatching {
                GroupSessionBuilder(core.store!!).process(SignalProtocolAddress(sender, senderDevice), SenderKeyDistributionMessage(b))
            }.onFailure { Log.w(TAG, "skdm failed", it) }
        }
        c.msg(Content.DATA)?.let { handleData(sender, DataMessage.decode(it), env, outgoing = false, destination = null) }
        c.msg(Content.SYNC)?.let { if (sender == self) handleSync(it, env) }
        c.msg(Content.RECEIPT)?.let { r ->
            val type = r.int(1) ?: 0
            val status = when (type) { ReceiptType.DELIVERY -> MsgStatus.DELIVERED; else -> MsgStatus.READ }
            r.longs(2).forEach { core.repo.setStatusByTimestamp(it, status) }
        }
        c.msg(Content.TYPING)?.let { t ->
            val conv = if (t.has(3)) null else core.repo.directId(sender)
            if (conv != null) {
                val started = (t.int(2) ?: 0) == 0
                typing.value = typing.value.toMutableMap().apply {
                    if (started) put(conv, System.currentTimeMillis() + 15_000) else remove(conv)
                }
            }
        }
    }

    private fun conversationFor(dm: DataMessage, peer: String): Conversation {
        val mk = dm.groupMasterKey
        return if (mk != null) {
            val gid = runCatching {
                Hex.toStringCondensed(GroupSecretParams.deriveFromMasterKey(GroupMasterKey(mk)).publicParams.groupIdentifier.serialize())
            }.getOrElse { Hex.toStringCondensed(mk).take(32) }
            core.repo.ensureGroup(gid, mk).also { core.groups.refreshTitleAsync(it) }
        } else core.repo.ensureDirect(peer, core.account.aci)
    }

    private fun handleData(sender: String, dm: DataMessage, env: Envelope, outgoing: Boolean, destination: String?) {
        val repo = core.repo
        val self = core.account.aci
        if (!outgoing && dm.profileKey != null && dm.profileKey.size == 32) {
            val known = repo.contact(sender)?.profileKey
            if (known == null || !known.contentEquals(dm.profileKey)) {
                repo.upsertContact(sender, profileKey = dm.profileKey)
                core.profiles.refreshAsync(sender)
            }
        } else if (!outgoing && repo.contact(sender) == null) {
            repo.upsertContact(sender)
        }

        val peer = if (outgoing) destination ?: self!! else sender
        val conv = conversationFor(dm, peer)
        val ts = if (dm.timestamp != 0L) dm.timestamp else env.timestamp

        dm.reaction?.let { r ->
            repo.setReaction(r.targetAuthorUuid, r.targetTimestamp, sender, if (r.remove) null else r.emoji)
            return
        }
        dm.deleteTargetTimestamp?.let { repo.remoteDelete(sender, it); return }
        if (dm.flags and 1 != 0) return // END_SESSION
        if (dm.flags and 4 != 0) { // PROFILE_KEY_UPDATE
            return
        }

        val atts = dm.attachments.map { a ->
            AttachmentInfo(a.contentType, a.fileName, a.size, null, a.width, a.height, pointerJson(a))
        } + listOfNotNull(dm.stickerAttachment?.let { a ->
            AttachmentInfo(a.contentType ?: "image/webp", STICKER_FILE, a.size, null, a.width, a.height, pointerJson(a))
        })
        val body = dm.body ?: if (dm.sticker && dm.stickerAttachment == null) "🧩 ${dm.stickerEmoji ?: "Стикер"}" else null
        if (body.isNullOrEmpty() && atts.isEmpty()) return

        val id = repo.insertMessage(
            conv.id, sender, outgoing, body, ts,
            if (outgoing) MsgStatus.SENT else MsgStatus.RECEIVED, atts,
            quoteTs = dm.quote?.id, quoteAuthor = dm.quote?.authorUuid, quoteText = dm.quote?.text,
        )
        if (id < 0) return
        if (atts.isNotEmpty()) core.attachments.autoDownload(id, atts)
        if (!outgoing) {
            typing.value = typing.value - conv.id
            core.notifications.onIncoming(conv.id)
            core.scope.launchIo { core.sender.sendDeliveryReceipt(sender, ts) }
        }
    }

    private fun handleSync(s: ProtoMessage, env: Envelope) {
        val self = core.account.aci ?: return
        s.msg(1)?.let { sent ->
            val dmMsg = sent.msg(3) ?: return@let
            val dest = sent.string(7)
            handleData(self, DataMessage.decode(dmMsg), env, outgoing = true, destination = dest)
        }
        s.msg(2)?.let { contacts ->
            contacts.msg(1)?.let { blob -> core.scope.launchIo { runCatching { syncContacts(AttachmentPointer.decode(blob)) }.onFailure { Log.w(TAG, "contact sync failed", it); core.account.lastContactSync = System.currentTimeMillis() - 23 * 3600_000L } } }
        }
        s.msg(5)?.let { read -> core.repo.markReadByTimestamp(read.string(3) ?: return@let, read.long(2) ?: return@let) }
        s.all(5).drop(1).forEach { f -> f.asMessage().let { r -> core.repo.markReadByTimestamp(r.string(3) ?: return@let, r.long(2) ?: return@let) } }
        s.msg(6)?.let { blocked -> blocked.all(3).forEach { core.repo.upsertContact(it.asString, blocked = true) } }
        s.msg(13)?.let { keys -> Log.i(TAG, "received keys sync (${keys.fields.size} fields)") }
    }

    private fun pointerJson(a: AttachmentPointer): String = JSONObject()
        .put("cdn", a.cdnNumber).put("id", a.cdnId?.toString()).put("key", a.cdnKey)
        .put("k", a.key?.let { B64.enc(it) }).put("d", a.digest?.let { B64.enc(it) })
        .toString()

    /** Contact sync blob: stream of [varint len][ContactDetails][avatar bytes]. */
    fun syncContacts(blob: AttachmentPointer) {
        val idOrKey = blob.cdnKey ?: blob.cdnId?.toString() ?: return
        val enc = core.api.downloadCdn(blob.cdnNumber, idOrKey)
        val plain = AttachmentCipher.decrypt(enc, blob.key ?: return, blob.digest)
        val reader = ProtoReader(plain)
        var count = 0
        while (!reader.eof()) {
            val len = reader.readVarint().toInt()
            val m = ProtoMessage.parse(reader.readBytes(len))
            val avatar = m.msg(3)
            val d = ContactDetails(
                number = m.string(1), name = m.string(2), uuid = m.string(9), profileKey = m.bytes(6),
                blocked = m.bool(7) ?: false, archived = m.bool(11) ?: false, inboxPosition = m.int(10),
                avatarLength = avatar?.int(2) ?: 0,
            )
            if (d.avatarLength > 0) reader.readBytes(d.avatarLength)
            val uuid = d.uuid ?: continue
            core.repo.upsertContact(uuid, e164 = d.number, systemName = d.name, profileKey = d.profileKey, blocked = d.blocked)
            if (d.profileKey != null) core.profiles.refreshAsync(uuid)
            count++
        }
        core.account.lastContactSync = System.currentTimeMillis()
        Log.i(TAG, "contact sync: $count contacts")
    }

    companion object {
        private const val TAG = "Receiver"
        /** Marks an attachment that is a Signal sticker image (rendered without a bubble). */
        const val STICKER_FILE = "sticker.webp"
    }
}
