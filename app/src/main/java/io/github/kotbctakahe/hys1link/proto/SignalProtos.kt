package io.github.kotbctakahe.hys1link.proto

/*
 * Field numbers extracted from Link Messenger 7.4.4 (SignalServiceProtos / WebSocketProtos /
 * ProvisioningProtos *_FIELD_NUMBER constants).
 */

object WsType { const val REQUEST = 1; const val RESPONSE = 2 }

data class WsRequest(val verb: String, val path: String, val body: ByteArray?, val id: Long, val headers: List<String>)
data class WsResponse(val id: Long, val status: Int, val message: String?, val body: ByteArray?, val headers: List<String>)

object WebSocketProto {
    fun decode(bytes: ByteArray): Pair<WsRequest?, WsResponse?> {
        val m = ProtoMessage.parse(bytes)
        val req = m.msg(2)?.let {
            WsRequest(it.string(1) ?: "", it.string(2) ?: "", it.bytes(3), it.long(4) ?: 0, it.all(5).map { h -> h.asString })
        }
        val resp = m.msg(3)?.let {
            WsResponse(it.long(1) ?: 0, it.int(2) ?: 0, it.string(3), it.bytes(4), it.all(5).map { h -> h.asString })
        }
        return req to resp
    }

    fun request(id: Long, verb: String, path: String, body: ByteArray? = null, headers: List<String> = emptyList()): ByteArray {
        val r = ProtoWriter().string(1, verb).string(2, path).bytes(3, body).uint(4, id)
        headers.forEach { r.string(5, it) }
        return ProtoWriter().uint(1, WsType.REQUEST).message(2, r).toByteArray()
    }

    fun response(id: Long, status: Int, message: String): ByteArray {
        val r = ProtoWriter().uint(1, id).uint(2, status).string(3, message)
        return ProtoWriter().uint(1, WsType.RESPONSE).message(3, r).toByteArray()
    }
}

object EnvelopeType {
    const val CIPHERTEXT = 1
    const val PREKEY_BUNDLE = 3
    const val RECEIPT = 5
    const val UNIDENTIFIED_SENDER = 6
    const val PLAINTEXT_CONTENT = 8
    /** Link-specific: server-initiated headless device link (see README, "vds-system"). */
    const val LINK_DEVICE_REQUEST = 15
}

data class Envelope(
    val type: Int,
    val sourceUuid: String?,
    val sourceDevice: Int,
    val timestamp: Long,
    val content: ByteArray?,
    val serverGuid: String?,
    val serverTimestamp: Long,
    val destinationUuid: String?,
) {
    companion object {
        fun decode(b: ByteArray): Envelope {
            val m = ProtoMessage.parse(b)
            return Envelope(
                type = m.int(1) ?: 0,
                sourceUuid = m.string(11),
                sourceDevice = m.int(7) ?: 1,
                timestamp = m.long(5) ?: 0,
                content = m.bytes(8),
                serverGuid = m.string(9),
                serverTimestamp = m.long(10) ?: 0,
                destinationUuid = m.string(13),
            )
        }
    }
}

data class AttachmentPointer(
    val cdnId: Long?, val cdnKey: String?, val cdnNumber: Int,
    val contentType: String?, val key: ByteArray?, val size: Int?, val digest: ByteArray?,
    val fileName: String?, val width: Int?, val height: Int?, val caption: String?, val blurHash: String?,
) {
    companion object {
        fun decode(m: ProtoMessage) = AttachmentPointer(
            cdnId = m.long(1), cdnKey = m.string(15), cdnNumber = m.int(14) ?: 0,
            contentType = m.string(2), key = m.bytes(3), size = m.int(4), digest = m.bytes(6),
            fileName = m.string(7), width = m.int(9), height = m.int(10), caption = m.string(11), blurHash = m.string(12),
        )
    }
}

/** Outgoing attachment pointer (fields per Link's SignalServiceProtos.AttachmentPointer). */
data class OutAttachment(
    val cdnNumber: Int, val cdnKey: String, val contentType: String, val key: ByteArray, val size: Int, val digest: ByteArray,
    val fileName: String?, val width: Int?, val height: Int?, val caption: String? = null, val uploadTimestamp: Long = System.currentTimeMillis(),
    val flags: Int? = null,
) {
    fun encode(): ProtoWriter = ProtoWriter()
        .string(2, contentType).bytes(3, key).uint(4, size).bytes(6, digest).string(7, fileName)
        .uint(8, flags).uint(9, width).uint(10, height).string(11, caption).uint(13, uploadTimestamp)
        .uint(14, cdnNumber).string(15, cdnKey)
}

data class Mention(val start: Int, val length: Int, val uuid: String)
data class LinkPreview(val url: String, val title: String?, val description: String?, val image: AttachmentPointer?)

data class Quote(val id: Long, val authorUuid: String?, val text: String?)
data class Reaction(val emoji: String, val remove: Boolean, val targetAuthorUuid: String?, val targetTimestamp: Long)

data class DataMessage(
    val body: String?,
    val attachments: List<AttachmentPointer>,
    val groupMasterKey: ByteArray?,
    val flags: Int,
    val expireTimer: Int,
    val profileKey: ByteArray?,
    val timestamp: Long,
    val quote: Quote?,
    val reaction: Reaction?,
    val deleteTargetTimestamp: Long?,
    val sticker: Boolean,
    val stickerAttachment: AttachmentPointer? = null,
    val stickerEmoji: String? = null,
    val mentions: List<Mention> = emptyList(),
    val previews: List<LinkPreview> = emptyList(),
    val viewOnce: Boolean = false,
) {
    companion object {
        fun decode(m: ProtoMessage) = DataMessage(
            body = m.string(1),
            attachments = m.all(2).map { AttachmentPointer.decode(it.asMessage()) },
            groupMasterKey = m.msg(15)?.bytes(1),
            flags = m.int(4) ?: 0,
            expireTimer = m.int(5) ?: 0,
            profileKey = m.bytes(6),
            timestamp = m.long(7) ?: 0,
            quote = m.msg(8)?.let { Quote(it.long(1) ?: 0, it.string(5), it.string(3)) },
            reaction = m.msg(16)?.let { Reaction(it.string(1) ?: "", it.bool(2) ?: false, it.string(4), it.long(5) ?: 0) },
            deleteTargetTimestamp = m.msg(17)?.long(1),
            sticker = m.has(11),
            stickerAttachment = m.msg(11)?.msg(4)?.let { AttachmentPointer.decode(it) },
            stickerEmoji = m.msg(11)?.string(5),
            // BodyRange: 1 start 2 length 3 mentionUuid
            mentions = m.all(18).mapNotNull { f -> f.asMessage().let { r -> r.string(3)?.let { Mention(r.int(1) ?: 0, r.int(2) ?: 1, it) } } },
            // Preview: 1 url 2 title 3 image 4 description
            previews = m.all(10).mapNotNull { f -> f.asMessage().let { p -> p.string(1)?.let { LinkPreview(it, p.string(2), p.string(4), p.msg(3)?.let(AttachmentPointer::decode)) } } },
            viewOnce = m.bool(14) ?: false,
        )
    }
}

object Content {
    const val DATA = 1
    const val SYNC = 2
    const val CALL = 3
    const val NULL = 4
    const val RECEIPT = 5
    const val TYPING = 6
    const val SKDM = 7
    const val DECRYPTION_ERROR = 8
    const val STORY = 9

    fun dataMessage(body: String?, timestamp: Long, profileKey: ByteArray?, groupMasterKey: ByteArray? = null,
                    groupRevision: Int? = null, quote: Quote? = null, attachments: List<OutAttachment> = emptyList(),
                    expireTimer: Int = 0, mentions: List<Mention> = emptyList(), viewOnce: Boolean = false,
                    flags: Int? = null, deleteTarget: Long? = null): ProtoWriter {
        val dm = ProtoWriter().string(1, body?.takeIf { it.isNotEmpty() })
        attachments.forEach { dm.message(2, it.encode()) }
        dm.uint(4, flags)
        if (expireTimer > 0) dm.uint(5, expireTimer)
        mentions.forEach { dm.message(18, ProtoWriter().uint(1, it.start).uint(2, it.length).string(3, it.uuid)) }
        if (viewOnce) dm.bool(14, true)
        if (deleteTarget != null) dm.message(17, ProtoWriter().uint(1, deleteTarget))
        if (groupMasterKey != null) dm.message(15, ProtoWriter().bytes(1, groupMasterKey).uint(2, groupRevision ?: 0))
        dm.bytes(6, profileKey)
        if (quote != null) dm.message(8, ProtoWriter().uint(1, quote.id).string(5, quote.authorUuid).string(3, quote.text))
        dm.uint(7, timestamp)
        return dm
    }

    fun reaction(emoji: String, remove: Boolean, targetAuthor: String, targetTs: Long, timestamp: Long, profileKey: ByteArray?,
                 groupMasterKey: ByteArray? = null, groupRevision: Int? = null): ProtoWriter =
        ProtoWriter()
            .message(16, ProtoWriter().string(1, emoji).bool(2, remove).string(4, targetAuthor).uint(5, targetTs))
            .bytes(6, profileKey).uint(7, timestamp)
            .apply { if (groupMasterKey != null) message(15, ProtoWriter().bytes(1, groupMasterKey).uint(2, groupRevision ?: 0)) }

    fun withData(dm: ProtoWriter) = ProtoWriter().message(DATA, dm).toByteArray()

    fun syncSent(destinationUuid: String?, timestamp: Long, dm: ProtoWriter): ByteArray {
        val sent = ProtoWriter().uint(2, timestamp).message(3, dm).string(7, destinationUuid)
        return ProtoWriter().message(SYNC, ProtoWriter().message(1, sent).bytes(8, randomPadding())).toByteArray()
    }

    fun syncRequest(type: Int): ByteArray =
        ProtoWriter().message(SYNC, ProtoWriter().message(4, ProtoWriter().uint(1, type)).bytes(8, randomPadding())).toByteArray()

    /** SyncMessage.Blocked: 1 numbers[] 3 uuids[] — full list replaces the primary's list. */
    fun syncBlocked(uuids: List<String>, numbers: List<String>): ByteArray {
        val b = ProtoWriter(); numbers.forEach { b.string(1, it) }; uuids.forEach { b.string(3, it) }
        return ProtoWriter().message(SYNC, ProtoWriter().message(6, b)).toByteArray()
    }

    fun syncViewOnceOpen(senderUuid: String, timestamp: Long): ByteArray =
        ProtoWriter().message(SYNC, ProtoWriter().message(11, ProtoWriter().string(3, senderUuid).uint(2, timestamp))).toByteArray()

    fun syncRead(senderUuid: String, timestamp: Long): ByteArray =
        ProtoWriter().message(SYNC, ProtoWriter().message(5, ProtoWriter().string(3, senderUuid).uint(2, timestamp))).toByteArray()

    fun receipt(type: Int, timestamps: List<Long>): ByteArray {
        val r = ProtoWriter().uint(1, type)
        timestamps.forEach { r.uint(2, it) }
        return ProtoWriter().message(RECEIPT, r).toByteArray()
    }

    fun typing(started: Boolean, timestamp: Long, groupId: ByteArray? = null): ByteArray =
        ProtoWriter().message(TYPING, ProtoWriter().uint(1, timestamp).uint(2, if (started) 0 else 1).bytes(3, groupId)).toByteArray()

    private fun randomPadding(): ByteArray {
        val r = java.security.SecureRandom()
        return ByteArray(1 + r.nextInt(512)).also { r.nextBytes(it) }
    }
}

object SyncRequestType { const val CONTACTS = 1; const val GROUPS = 2; const val BLOCKED = 3; const val CONFIGURATION = 4; const val KEYS = 5 }
object ReceiptType { const val DELIVERY = 0; const val READ = 1; const val VIEWED = 2 }

data class ContactDetails(
    val number: String?, val name: String?, val uuid: String?, val profileKey: ByteArray?,
    val blocked: Boolean, val archived: Boolean, val inboxPosition: Int?, val avatarLength: Int,
)
