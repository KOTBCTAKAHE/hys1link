package io.github.kotbctakahe.hys1link.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

data class Contact(
    val uuid: String, val e164: String?, val systemName: String?, val profileName: String?,
    val about: String?, val profileKey: ByteArray?, val avatarPath: String?, val blocked: Boolean,
) {
    val displayName: String get() = systemName?.takeIf { it.isNotBlank() } ?: profileName?.takeIf { it.isNotBlank() } ?: e164 ?: "Пользователь ${uuid.take(4)}"
}

object ConvKind { const val DIRECT = 0; const val GROUP = 1; const val NOTE_TO_SELF = 2 }

data class Conversation(
    val id: String, val kind: Int, val peer: String?, val groupMasterKey: ByteArray?, val title: String,
    val lastBody: String?, val lastTs: Long, val unread: Int, val pinned: Boolean, val avatarPath: String?,
    val expireTimer: Int = 0,
)

data class MentionInfo(val start: Int, val length: Int, val uuid: String)
data class PreviewInfo(val url: String, val title: String?, val description: String?, val imagePath: String?)

object MsgKind { const val NORMAL = 0; const val SYSTEM = 1 }

object MsgStatus { const val PENDING = 0; const val SENT = 1; const val DELIVERED = 2; const val READ = 3; const val FAILED = -1; const val RECEIVED = 10 }

data class AttachmentInfo(val contentType: String?, val fileName: String?, val size: Int?, val localPath: String?,
                          val width: Int?, val height: Int?, val pointer: String?)

data class Message(
    val id: Long, val conv: String, val author: String?, val outgoing: Boolean, val body: String?,
    val sentTs: Long, val receivedTs: Long, val status: Int, val attachments: List<AttachmentInfo>,
    val quoteTs: Long?, val quoteAuthor: String?, val quoteText: String?, val reactions: Map<String, String>, val deleted: Boolean,
    val expiresIn: Int = 0, val expireStarted: Long = 0, val viewOnce: Int = 0, val mentions: List<MentionInfo> = emptyList(),
    val preview: PreviewInfo? = null, val kind: Int = MsgKind.NORMAL,
)

class Repo(val db: Db) {
    private val w get() = db.writableDatabase
    private val r get() = db.readableDatabase

    // ---- contacts ----
    fun contact(uuid: String): Contact? = r.rawQuery("SELECT * FROM contacts WHERE uuid=?", arrayOf(uuid)).use {
        if (it.moveToFirst()) Contact(
            it.str("uuid")!!, it.str("e164"), it.str("system_name"), it.str("profile_name"), it.str("about"),
            it.blob("profile_key"), it.str("avatar_path"), it.lng("blocked") != 0L
        ) else null
    }

    fun contacts(): List<Contact> = r.rawQuery("SELECT uuid FROM contacts WHERE blocked=0", null).use { c ->
        buildList { while (c.moveToNext()) contact(c.getString(0))?.let { add(it) } }
    }.sortedBy { it.displayName.lowercase() }

    fun blockedContacts(): List<Contact> = r.rawQuery("SELECT uuid FROM contacts WHERE blocked=1", null).use { c ->
        buildList { while (c.moveToNext()) contact(c.getString(0))?.let { add(it) } }
    }

    fun upsertContact(uuid: String, e164: String? = null, systemName: String? = null, profileName: String? = null,
                      about: String? = null, profileKey: ByteArray? = null, avatarPath: String? = null, blocked: Boolean? = null) {
        val existing = contact(uuid)
        val cv = ContentValues().apply {
            put("uuid", uuid)
            put("e164", e164 ?: existing?.e164)
            put("system_name", systemName ?: existing?.systemName)
            put("profile_name", profileName ?: existing?.profileName)
            put("about", about ?: existing?.about)
            put("profile_key", profileKey ?: existing?.profileKey)
            put("avatar_path", avatarPath ?: existing?.avatarPath)
            put("blocked", if (blocked ?: existing?.blocked == true) 1 else 0)
            put("updated", System.currentTimeMillis())
        }
        w.insertWithOnConflict("contacts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        // keep direct conversation titles in sync
        contact(uuid)?.let { c -> w.execSQL("UPDATE conversations SET title=? WHERE id=?", arrayOf(c.displayName, directId(uuid))) }
        db.notifyChanged()
    }

    // ---- conversations ----
    fun directId(uuid: String) = "d:$uuid"
    fun groupId(groupIdHex: String) = "g:$groupIdHex"

    fun ensureDirect(uuid: String, self: String?): Conversation {
        val id = directId(uuid)
        conversation(id)?.let { return it }
        val kind = if (uuid == self) ConvKind.NOTE_TO_SELF else ConvKind.DIRECT
        val title = if (kind == ConvKind.NOTE_TO_SELF) "Заметки" else contact(uuid)?.displayName ?: "Пользователь ${uuid.take(4)}"
        w.insertWithOnConflict("conversations", null, ContentValues().apply {
            put("id", id); put("kind", kind); put("peer", uuid); put("title", title)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        db.notifyChanged()
        return conversation(id)!!
    }

    fun ensureGroup(groupIdHex: String, masterKey: ByteArray): Conversation {
        val id = groupId(groupIdHex)
        conversation(id)?.let { return it }
        w.insertWithOnConflict("conversations", null, ContentValues().apply {
            put("id", id); put("kind", ConvKind.GROUP); put("group_master_key", masterKey); put("title", "Группа ${groupIdHex.take(6)}")
        }, SQLiteDatabase.CONFLICT_IGNORE)
        db.notifyChanged()
        return conversation(id)!!
    }

    fun renameConversation(id: String, title: String) {
        w.execSQL("UPDATE conversations SET title=? WHERE id=?", arrayOf(title, id)); db.notifyChanged()
    }

    fun setConversationAvatar(id: String, path: String?) {
        w.execSQL("UPDATE conversations SET avatar_path=? WHERE id=?", arrayOf(path, id)); db.notifyChanged()
    }

    fun groupConversations(): List<Conversation> = r.rawQuery(
        "SELECT c.*, c.avatar_path AS avatar FROM conversations c WHERE c.kind=1", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.toConversation()) } }

    fun setPinned(id: String, pinned: Boolean) {
        w.execSQL("UPDATE conversations SET pinned=? WHERE id=?", arrayOf(if (pinned) 1 else 0, id)); db.notifyChanged()
    }

    fun deleteConversation(id: String) {
        w.delete("messages", "conv=?", arrayOf(id)); w.delete("conversations", "id=?", arrayOf(id)); db.notifyChanged()
    }

    fun conversation(id: String): Conversation? = r.rawQuery(
        "SELECT c.*, COALESCE(k.avatar_path, c.avatar_path) AS avatar FROM conversations c LEFT JOIN contacts k ON k.uuid=c.peer WHERE c.id=?", arrayOf(id)
    ).use { if (it.moveToFirst()) it.toConversation() else null }

    fun conversations(): List<Conversation> = r.rawQuery(
        "SELECT c.*, COALESCE(k.avatar_path, c.avatar_path) AS avatar FROM conversations c LEFT JOIN contacts k ON k.uuid=c.peer " +
            "WHERE c.archived=0 AND (c.last_ts>0 OR c.kind=2) ORDER BY c.pinned DESC, c.last_ts DESC", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.toConversation()) } }

    private fun android.database.Cursor.toConversation() = Conversation(
        str("id")!!, lng("kind").toInt(), str("peer"), blob("group_master_key"), str("title") ?: "",
        str("last_body"), lng("last_ts"), lng("unread").toInt(), lng("pinned") != 0L, str("avatar"),
        lng("expire_timer").toInt(),
    )

    fun markRead(convId: String) {
        w.execSQL("UPDATE conversations SET unread=0 WHERE id=?", arrayOf(convId)); db.notifyChanged()
    }

    fun unreadIncoming(convId: String, sinceTs: Long = 0): List<Message> =
        messages(convId).filter { !it.outgoing && it.status == MsgStatus.RECEIVED && it.sentTs >= sinceTs }

    // ---- messages ----
    /** Returns new row id, or -1 if this message already exists (dedupe on conv+author+timestamp). */
    fun insertMessage(conv: String, author: String?, outgoing: Boolean, body: String?, sentTs: Long, status: Int,
                      attachments: List<AttachmentInfo> = emptyList(), quoteTs: Long? = null, quoteAuthor: String? = null,
                      quoteText: String? = null, countUnread: Boolean = !outgoing, expiresIn: Int = 0, expireStarted: Long = 0,
                      viewOnce: Boolean = false, mentions: List<MentionInfo> = emptyList(), preview: PreviewInfo? = null,
                      kind: Int = MsgKind.NORMAL): Long {
        val cv = ContentValues().apply {
            put("expires_in", expiresIn); put("expire_started", expireStarted); put("view_once", if (viewOnce) 1 else 0); put("kind", kind)
            if (mentions.isNotEmpty()) put("mentions", JSONArray().apply { mentions.forEach { put(JSONObject().put("s", it.start).put("l", it.length).put("u", it.uuid)) } }.toString())
            if (preview != null) put("preview", JSONObject().put("url", preview.url).put("t", preview.title).put("d", preview.description).put("i", preview.imagePath).toString())
            put("conv", conv); put("author", author); put("outgoing", if (outgoing) 1 else 0); put("body", body)
            put("sent_ts", sentTs); put("received_ts", System.currentTimeMillis()); put("status", status)
            put("attachments", attachmentsToJson(attachments))
            quoteTs?.let { put("quote_ts", it) }; put("quote_author", quoteAuthor); put("quote_text", quoteText)
        }
        val id = w.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (id == -1L) return -1
        val preview = when {
            viewOnce -> "🔥 Одноразовое сообщение"
            body != null && body.startsWith("/link=") -> "🧩 Стикер"
            !body.isNullOrBlank() -> body
            else -> attachments.firstOrNull()?.let { previewFor(it) } ?: ""
        }
        w.execSQL(
            "UPDATE conversations SET last_body=?, last_ts=MAX(last_ts, ?), archived=0, unread=unread+? WHERE id=?",
            arrayOf(preview, sentTs, if (countUnread) 1 else 0, conv)
        )
        db.notifyChanged()
        return id
    }

    private fun previewFor(a: AttachmentInfo): String = when {
        a.fileName == "sticker.webp" -> "🧩 Стикер"
        a.contentType?.startsWith("image/") == true -> "🖼 Фото"
        a.contentType?.startsWith("video/") == true -> "🎬 Видео"
        a.contentType?.startsWith("audio/") == true -> "🎤 Голосовое"
        else -> "📎 ${a.fileName ?: "Файл"}"
    }

    fun messages(conv: String, limit: Int = 500): List<Message> = r.rawQuery(
        "SELECT * FROM (SELECT * FROM messages WHERE conv=? ORDER BY sent_ts DESC LIMIT ?) ORDER BY sent_ts ASC",
        arrayOf(conv, limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                Message(
                    c.lng("id"), c.str("conv")!!, c.str("author"), c.lng("outgoing") != 0L, c.str("body"),
                    c.lng("sent_ts"), c.lng("received_ts"), c.lng("status").toInt(), attachmentsFromJson(c.str("attachments")),
                    c.getColumnIndex("quote_ts").let { i -> if (c.isNull(i)) null else c.getLong(i) }, c.str("quote_author"), c.str("quote_text"),
                    reactionsFromJson(c.str("reactions")), c.lng("deleted") != 0L,
                    c.lng("expires_in").toInt(), c.lng("expire_started"), c.lng("view_once").toInt(),
                    mentionsFromJson(c.str("mentions")), previewFromJson(c.str("preview")), c.lng("kind").toInt(),
                )
            )
        }
    }

    fun setStatusByTimestamp(sentTs: Long, status: Int) {
        w.execSQL("UPDATE messages SET status=MAX(status, ?) WHERE outgoing=1 AND sent_ts=? AND status>=0 AND status<10", arrayOf(status, sentTs))
        db.notifyChanged()
    }

    fun setStatus(id: Long, status: Int) {
        w.execSQL("UPDATE messages SET status=? WHERE id=?", arrayOf(status, id)); db.notifyChanged()
    }

    fun markReadByTimestamp(author: String, sentTs: Long) {
        r.rawQuery("SELECT conv FROM messages WHERE author=? AND sent_ts=?", arrayOf(author, sentTs.toString())).use { c ->
            while (c.moveToNext()) {
                val conv = c.getString(0)
                w.execSQL("UPDATE messages SET status=11 WHERE conv=? AND outgoing=0 AND sent_ts<=?", arrayOf(conv, sentTs))
                w.execSQL(
                    "UPDATE conversations SET unread=(SELECT COUNT(*) FROM messages WHERE conv=? AND outgoing=0 AND status=10) WHERE id=?",
                    arrayOf(conv, conv)
                )
                startExpiration(conv)
            }
        }
        db.notifyChanged()
    }

    fun markConversationIncomingRead(conv: String) {
        w.execSQL("UPDATE messages SET status=11 WHERE conv=? AND outgoing=0 AND status=10", arrayOf(conv))
        startExpiration(conv)
        markRead(conv)
    }

    fun findMessage(author: String?, sentTs: Long): Message? {
        val cursor = if (author != null)
            r.rawQuery("SELECT conv FROM messages WHERE sent_ts=? AND author=? LIMIT 1", arrayOf(sentTs.toString(), author))
        else r.rawQuery("SELECT conv FROM messages WHERE sent_ts=? LIMIT 1", arrayOf(sentTs.toString()))
        return cursor.use { if (it.moveToFirst()) messages(it.getString(0)).firstOrNull { m -> m.sentTs == sentTs } else null }
    }

    fun setReaction(targetAuthor: String?, targetTs: Long, reactor: String, emoji: String?) {
        val m = findMessage(targetAuthor, targetTs) ?: return
        val map = m.reactions.toMutableMap()
        if (emoji == null) map.remove(reactor) else map[reactor] = emoji
        w.execSQL("UPDATE messages SET reactions=? WHERE id=?", arrayOf(JSONObject(map as Map<*, *>).toString(), m.id))
        db.notifyChanged()
    }

    fun remoteDelete(author: String, targetTs: Long) {
        w.execSQL("UPDATE messages SET deleted=1, body=NULL, attachments=NULL WHERE author=? AND sent_ts=?", arrayOf(author, targetTs))
        db.notifyChanged()
    }

    fun updateAttachmentPath(messageId: Long, index: Int, path: String) {
        val m = r.rawQuery("SELECT attachments FROM messages WHERE id=?", arrayOf(messageId.toString())).use { if (it.moveToFirst()) it.str("attachments") else null }
        val list = attachmentsFromJson(m).toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(localPath = path)
        w.execSQL("UPDATE messages SET attachments=? WHERE id=?", arrayOf(attachmentsToJson(list), messageId))
        db.notifyChanged()
    }

    private fun attachmentsToJson(list: List<AttachmentInfo>): String? = if (list.isEmpty()) null else JSONArray().apply {
        list.forEach {
            put(JSONObject().put("ct", it.contentType).put("fn", it.fileName).put("size", it.size).put("path", it.localPath)
                .put("w", it.width).put("h", it.height).put("ptr", it.pointer))
        }
    }.toString()

    private fun attachmentsFromJson(s: String?): List<AttachmentInfo> {
        if (s.isNullOrBlank()) return emptyList()
        val a = JSONArray(s)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            AttachmentInfo(o.optStringOrNull("ct"), o.optStringOrNull("fn"), o.optIntOrNull("size"), o.optStringOrNull("path"),
                o.optIntOrNull("w"), o.optIntOrNull("h"), o.optStringOrNull("ptr"))
        }
    }

    private fun mentionsFromJson(s: String?): List<MentionInfo> = if (s.isNullOrBlank()) emptyList() else runCatching {
        val a = JSONArray(s); (0 until a.length()).map { a.getJSONObject(it).let { o -> MentionInfo(o.getInt("s"), o.getInt("l"), o.getString("u")) } }
    }.getOrDefault(emptyList())

    private fun previewFromJson(s: String?): PreviewInfo? = if (s.isNullOrBlank()) null else runCatching {
        JSONObject(s).let { o -> PreviewInfo(o.getString("url"), o.optStringOrNull("t"), o.optStringOrNull("d"), o.optStringOrNull("i")) }
    }.getOrNull()

    fun setPreviewImage(messageId: Long, path: String) {
        val cur = r.rawQuery("SELECT preview FROM messages WHERE id=?", arrayOf(messageId.toString())).use { if (it.moveToFirst()) it.str("preview") else null } ?: return
        val o = JSONObject(cur).put("i", path)
        w.execSQL("UPDATE messages SET preview=? WHERE id=?", arrayOf(o.toString(), messageId)); db.notifyChanged()
    }

    // ---- disappearing messages ----
    fun setExpireTimer(convId: String, seconds: Int) {
        w.execSQL("UPDATE conversations SET expire_timer=? WHERE id=?", arrayOf(seconds, convId)); db.notifyChanged()
    }

    /** Starts the countdown for messages in [convId] that were read (incoming) / sent (outgoing). */
    fun startExpiration(convId: String, now: Long = System.currentTimeMillis()) {
        w.execSQL("UPDATE messages SET expire_started=? WHERE conv=? AND expires_in>0 AND expire_started=0 AND (outgoing=1 OR status=11)", arrayOf(now, convId))
    }

    /** Deletes messages whose timer ran out; returns how many were removed. */
    fun deleteExpired(now: Long = System.currentTimeMillis()): Int {
        val ids = r.rawQuery("SELECT id, attachments FROM messages WHERE expires_in>0 AND expire_started>0 AND expire_started + expires_in*1000 <= ?",
            arrayOf(now.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.str("attachments")) } }
        if (ids.isEmpty()) return 0
        ids.forEach { (id, att) ->
            attachmentsFromJson(att).forEach { a -> a.localPath?.let { java.io.File(it).delete() } }
            w.delete("messages", "id=?", arrayOf(id.toString()))
        }
        db.notifyChanged()
        return ids.size
    }

    fun nextExpiry(): Long? = r.rawQuery("SELECT MIN(expire_started + expires_in*1000) FROM messages WHERE expires_in>0 AND expire_started>0", null)
        .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    // ---- misc ----
    fun insertSystem(conv: String, text: String, ts: Long = System.currentTimeMillis()) =
        insertMessage(conv, "system", false, text, ts, 11, countUnread = false, kind = MsgKind.SYSTEM)

    fun deleteLocal(messageId: Long) {
        r.rawQuery("SELECT attachments FROM messages WHERE id=?", arrayOf(messageId.toString())).use { c ->
            if (c.moveToFirst()) attachmentsFromJson(c.str("attachments")).forEach { a -> a.localPath?.let { java.io.File(it).delete() } }
        }
        w.delete("messages", "id=?", arrayOf(messageId.toString())); db.notifyChanged()
    }

    /** View-once: after opening, drop the media and remember it was viewed. */
    fun markViewOnceViewed(author: String?, sentTs: Long) {
        val m = findMessage(author, sentTs) ?: return
        m.attachments.forEach { a -> a.localPath?.let { java.io.File(it).delete() } }
        w.execSQL("UPDATE messages SET view_once=2, attachments=NULL WHERE id=?", arrayOf(m.id)); db.notifyChanged()
    }

    fun message(id: Long): Message? = r.rawQuery("SELECT conv FROM messages WHERE id=?", arrayOf(id.toString()))
        .use { if (it.moveToFirst()) messages(it.getString(0), 2000).firstOrNull { m -> m.id == id } else null }

    private fun reactionsFromJson(s: String?): Map<String, String> {
        if (s.isNullOrBlank()) return emptyMap()
        val o = JSONObject(s)
        return o.keys().asSequence().associateWith { o.getString(it) }
    }
}

fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
