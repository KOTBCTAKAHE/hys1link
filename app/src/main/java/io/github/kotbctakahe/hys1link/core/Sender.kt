package io.github.kotbctakahe.hys1link.core

import android.util.Log
import io.github.kotbctakahe.hys1link.crypto.Padding
import io.github.kotbctakahe.hys1link.net.LinkApi
import io.github.kotbctakahe.hys1link.net.OutgoingMessage
import io.github.kotbctakahe.hys1link.proto.Content
import io.github.kotbctakahe.hys1link.proto.EnvelopeType
import io.github.kotbctakahe.hys1link.proto.Quote
import io.github.kotbctakahe.hys1link.proto.ReceiptType
import io.github.kotbctakahe.hys1link.proto.SyncRequestType
import io.github.kotbctakahe.hys1link.store.ConvKind
import io.github.kotbctakahe.hys1link.store.MsgStatus
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage

/** Encrypts and sends Content to all devices of a recipient, handling 409/410 device mismatches. */
class Sender(private val core: Core) {

    /** Low-level: deliver [content] (unpadded Content bytes) to every device of [uuid]. */
    @Synchronized
    fun sendContent(uuid: String, content: ByteArray, timestamp: Long, urgent: Boolean = true, online: Boolean = false) {
        val store = core.store ?: error("Not linked")
        val self = core.account.aci
        val myDevice = core.account.deviceId
        val padded = Padding.pad(content)

        var devices = store.devicesWithSession(uuid).filterNot { uuid == self && it == myDevice }
        if (devices.isEmpty() && uuid != self) devices = fetchSessions(uuid, "*")
        if (devices.isEmpty() && uuid == self) devices = fetchSessions(uuid, "*").filterNot { it == myDevice }

        repeat(4) { attempt ->
            val messages = devices.map { d -> encrypt(uuid, d, padded) }
            when (val r = core.api.sendMessages(uuid, timestamp, messages, online, urgent)) {
                LinkApi.SendResult.Ok -> return
                is LinkApi.SendResult.Mismatched -> {
                    r.extra.forEach { store.deleteSession(SignalProtocolAddress(uuid, it)) }
                    r.missing.forEach { d -> fetchSessions(uuid, d.toString()) }
                    devices = (devices - r.extra.toSet() + r.missing).distinct()
                }
                is LinkApi.SendResult.Stale -> {
                    r.stale.forEach { d ->
                        store.deleteSession(SignalProtocolAddress(uuid, d))
                        fetchSessions(uuid, d.toString())
                    }
                }
            }
            Log.i(TAG, "retry send to $uuid (attempt ${attempt + 1}) devices=$devices")
        }
        error("Не удалось доставить сообщение после нескольких попыток")
    }

    private fun encrypt(uuid: String, device: Int, padded: ByteArray): OutgoingMessage {
        val store = core.store!!
        val addr = SignalProtocolAddress(uuid, device)
        val cipher = SessionCipher(store, addr)
        val msg = cipher.encrypt(padded)
        val type = when (msg.type) {
            CiphertextMessage.PREKEY_TYPE -> EnvelopeType.PREKEY_BUNDLE
            CiphertextMessage.WHISPER_TYPE -> EnvelopeType.CIPHERTEXT
            else -> error("Unexpected ciphertext type ${msg.type}")
        }
        return OutgoingMessage(type, device, cipher.remoteRegistrationId, msg.serialize())
    }

    /** Fetch bundles and build sessions; returns the device ids that now have sessions. */
    private fun fetchSessions(uuid: String, device: String): List<Int> {
        val store = core.store!!
        val bundles = runCatching { core.api.preKeyBundles(uuid, device) }.getOrElse {
            Log.w(TAG, "no bundles for $uuid/$device", it); return emptyList()
        }
        return bundles.mapNotNull { b ->
            if (uuid == core.account.aci && b.deviceId == core.account.deviceId) return@mapNotNull null
            runCatching {
                SessionBuilder(store, SignalProtocolAddress(uuid, b.deviceId)).process(b)
                b.deviceId
            }.onFailure { Log.w(TAG, "session build failed $uuid.${b.deviceId}", it) }.getOrNull()
        }
    }

    // ---------------- high level ----------------

    /** Sends a text message to a conversation and records it locally. */
    fun sendText(convId: String, body: String, quote: Quote? = null) {
        val repo = core.repo
        val conv = repo.conversation(convId) ?: return
        val self = core.account.aci ?: return
        if (conv.kind == ConvKind.GROUP) { core.groups.sendText(conv, body); return }
        val ts = System.currentTimeMillis()
        val msgId = repo.insertMessage(convId, self, true, body, ts, MsgStatus.PENDING,
            quoteTs = quote?.id, quoteAuthor = quote?.authorUuid, quoteText = quote?.text)
        try {
            when (conv.kind) {
                ConvKind.DIRECT, ConvKind.NOTE_TO_SELF -> {
                    val peer = conv.peer!!
                    val dm = Content.dataMessage(body, ts, core.account.profileKey, quote = quote)
                    if (peer != self) sendContent(peer, Content.withData(dm), ts)
                    runCatching { sendContent(self, Content.syncSent(peer, ts, Content.dataMessage(body, ts, core.account.profileKey, quote = quote)), ts, urgent = false) }
                        .onFailure { Log.w(TAG, "sync transcript failed", it) }
                }
                else -> Unit
            }
            repo.setStatus(msgId, MsgStatus.SENT)
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
            repo.setStatus(msgId, MsgStatus.FAILED)
            throw e
        }
    }

    fun sendReaction(convId: String, targetAuthor: String, targetTs: Long, emoji: String, remove: Boolean) {
        val conv = core.repo.conversation(convId) ?: return
        val self = core.account.aci ?: return
        if (conv.kind == ConvKind.GROUP) return
        val ts = System.currentTimeMillis()
        val dm = Content.reaction(emoji, remove, targetAuthor, targetTs, ts, core.account.profileKey)
        val peer = conv.peer!!
        if (peer != self) sendContent(peer, Content.withData(dm), ts)
        runCatching { sendContent(self, Content.syncSent(peer, ts, Content.reaction(emoji, remove, targetAuthor, targetTs, ts, core.account.profileKey)), ts, urgent = false) }
        core.repo.setReaction(targetAuthor, targetTs, self, if (remove) null else emoji)
    }

    fun sendReadReceipts(senderUuid: String, timestamps: List<Long>) {
        if (timestamps.isEmpty()) return
        val self = core.account.aci ?: return
        runCatching {
            if (core.account.sendReadReceipts && senderUuid != self)
                sendContent(senderUuid, Content.receipt(ReceiptType.READ, timestamps), System.currentTimeMillis(), urgent = false)
        }.onFailure { Log.w(TAG, "read receipt failed", it) }
        timestamps.forEach { ts ->
            runCatching { sendContent(self, Content.syncRead(senderUuid, ts), System.currentTimeMillis(), urgent = false) }
        }
    }

    fun sendDeliveryReceipt(senderUuid: String, timestamp: Long) {
        if (senderUuid == core.account.aci) return
        runCatching { sendContent(senderUuid, Content.receipt(ReceiptType.DELIVERY, listOf(timestamp)), System.currentTimeMillis(), urgent = false) }
            .onFailure { Log.w(TAG, "delivery receipt failed", it) }
    }

    fun sendTyping(convId: String, started: Boolean) {
        val conv = core.repo.conversation(convId) ?: return
        if (conv.kind != ConvKind.DIRECT) return
        runCatching { sendContent(conv.peer!!, Content.typing(started, System.currentTimeMillis()), System.currentTimeMillis(), urgent = false, online = true) }
    }

    fun requestInitialSync() {
        val self = core.account.aci ?: return
        listOf(SyncRequestType.CONTACTS, SyncRequestType.GROUPS, SyncRequestType.BLOCKED, SyncRequestType.CONFIGURATION, SyncRequestType.KEYS).forEach { t ->
            runCatching { sendContent(self, Content.syncRequest(t), System.currentTimeMillis(), urgent = false) }
                .onFailure { Log.w(TAG, "sync request $t failed", it) }
        }
    }

    companion object { private const val TAG = "Sender" }
}
