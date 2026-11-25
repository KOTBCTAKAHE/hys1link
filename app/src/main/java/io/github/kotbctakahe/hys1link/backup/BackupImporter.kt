package io.github.kotbctakahe.hys1link.backup

import android.util.Log
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.crypto.Hkdf
import io.github.kotbctakahe.hys1link.proto.ProtoMessage
import io.github.kotbctakahe.hys1link.store.AttachmentInfo
import io.github.kotbctakahe.hys1link.store.MsgStatus
import org.signal.libsignal.protocol.util.Hex
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads Link/Signal local ".backup" files (BackupRecordInputStream format, version 0 as written by Link 7.4.4,
 * version 1 also supported):
 *   [len][BackupFrame{header{iv,salt}}]  then  repeated  [len][AES-CTR(frame)][HMAC-10]  (+ raw attachment payloads)
 * key = SHA-512^250000(passphrase, salt)[0..32] → HKDF("Backup Export", 64) = cipherKey || macKey.
 */
class BackupStream(input: InputStream, passphrase: String) {
    private val inp = DataInputStream(input.buffered(1 shl 16))
    private val iv: ByteArray
    private var counter: Int
    private val version: Int
    private val cipherKey: ByteArray
    private val mac = Mac.getInstance("HmacSHA256")
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding")

    init {
        val headerLen = inp.readInt()
        require(headerLen in 1..4096) { "Это не файл бэкапа Link" }
        val frame = ProtoMessage.parse(ByteArray(headerLen).also { inp.readFully(it) })
        val header = frame.msg(1) ?: error("Бэкап не начинается с заголовка")
        iv = header.bytes(1)?.copyOf() ?: error("Нет IV")
        require(iv.size == 16) { "Неверная длина IV" }
        val salt = header.bytes(2)
        version = header.int(3) ?: 0
        val key = backupKey(passphrase, salt)
        val derived = Hkdf.derive(key, "Backup Export".toByteArray(), 64)
        cipherKey = derived.copyOfRange(0, 32)
        mac.init(SecretKeySpec(derived.copyOfRange(32, 64), "HmacSHA256"))
        counter = toInt(iv, 0)
    }

    private fun backupKey(passphrase: String, salt: ByteArray?): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        val input = passphrase.replace(" ", "").toByteArray()
        var hash = input
        if (salt != null) digest.update(salt)
        repeat(250_000) { digest.update(hash); hash = digest.digest(input) }
        return hash.copyOf(32)
    }

    private fun nextIv(): IvParameterSpec {
        putInt(iv, 0, counter++)
        return IvParameterSpec(iv.copyOf())
    }

    /** Returns null at end of stream. */
    fun readFrame(): ProtoMessage? {
        val lenBytes = ByteArray(4)
        try { inp.readFully(lenBytes) } catch (e: java.io.EOFException) { return null }
        val frameLen: Int
        if (version >= 1) {
            mac.update(lenBytes)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cipherKey, "AES"), nextIv())
            frameLen = toInt(cipher.update(lenBytes), 0)
        } else frameLen = toInt(lenBytes, 0)
        require(frameLen in 11..(64 shl 20)) { "Неверный пароль или повреждённый файл" }
        val frame = ByteArray(frameLen).also { inp.readFully(it) }
        mac.update(frame, 0, frameLen - 10)
        val ours = mac.doFinal().copyOf(10)
        if (!MessageDigest.isEqual(ours, frame.copyOfRange(frameLen - 10, frameLen))) error("Неверный пароль бэкапа (MAC не совпал)")
        if (version == 0) cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cipherKey, "AES"), nextIv())
        return ProtoMessage.parse(cipher.doFinal(frame, 0, frameLen - 10))
    }

    /** Reads an attachment/avatar/sticker payload that follows its frame; writes to [out] or discards. */
    fun readPayload(length: Int, out: OutputStream?) {
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cipherKey, "AES"), nextIv())
        mac.update(iv)
        val buf = ByteArray(1 shl 16)
        var left = length
        while (left > 0) {
            val n = inp.read(buf, 0, minOf(buf.size, left))
            if (n < 0) error("Файл бэкапа обрывается")
            mac.update(buf, 0, n)
            val p = cipher.update(buf, 0, n)
            if (p != null) out?.write(p)
            left -= n
        }
        cipher.doFinal()?.let { out?.write(it) }
        val ours = mac.doFinal().copyOf(10)
        val theirs = ByteArray(10).also { inp.readFully(it) }
        if (!MessageDigest.isEqual(ours, theirs)) error("Повреждённое вложение в бэкапе")
    }

    private fun toInt(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}

/**
 * Imports conversation history (not keys!) from a Link backup into this linked device.
 * Identity keys, sessions and pre-keys in the backup belong to the primary phone and are ignored.
 */
class BackupImporter(private val core: Core) {
    data class Progress(val frames: Int, val messages: Int, val attachments: Int, val phase: String)
    data class Result(val messages: Int, val conversations: Int, val attachments: Int, val skipped: Int)

    private class Table(val columns: List<String>) {
        fun idx(vararg names: String) = names.firstNotNullOfOrNull { n -> columns.indexOf(n).takeIf { it >= 0 } } ?: -1
    }

    private data class Recipient(val uuid: String?, val phone: String?, val groupId: String?, val name: String?, val profileKey: ByteArray?)
    private data class RawMsg(val id: Long, val mms: Boolean, val thread: Long, val recipient: Long, val sentTs: Long, val type: Long, val body: String?,
                              val quoteTs: Long?, val quoteAuthor: Long?, val quoteBody: String?)
    private data class Part(val mid: Long, val contentType: String?, val fileName: String?, val width: Int?, val height: Int?)

    fun run(input: InputStream, passphrase: String, onProgress: (Progress) -> Unit): Result {
        val stream = BackupStream(input, passphrase)
        val tables = HashMap<String, Table>()
        val recipients = HashMap<Long, Recipient>()
        val threads = HashMap<Long, Long>() // thread id -> recipient id
        val groupKeys = HashMap<String, Pair<ByteArray, String?>>() // signal group_id -> (master key, title)
        val msgs = ArrayList<RawMsg>()
        val parts = HashMap<Long, Part>()
        val partFiles = HashMap<Long, File>() // part row id -> decrypted file
        val tmpDir = File(core.context.cacheDir, "backup_import").apply { deleteRecursively(); mkdirs() }
        var frames = 0

        while (true) {
            val f = stream.readFrame() ?: break
            frames++
            if (frames % 500 == 0) onProgress(Progress(frames, msgs.size, partFiles.size, "Читаю бэкап"))
            if (f.has(6)) break // end
            f.msg(2)?.let { st -> handleStatement(st, tables, recipients, threads, groupKeys, msgs, parts) }
            f.msg(4)?.let { a -> // Attachment{1 rowId 2 attachmentId 3 length}
                val rowId = a.long(1) ?: 0; val len = a.int(3) ?: 0
                val ct = parts[rowId]?.contentType
                val keep = len <= 25 shl 20 && (ct?.startsWith("image/") == true || ct?.startsWith("audio/") == true)
                if (keep) {
                    val file = File(tmpDir, "p$rowId")
                    file.outputStream().use { stream.readPayload(len, it) }
                    partFiles[rowId] = file
                } else stream.readPayload(len, null)
            }
            f.msg(7)?.let { av -> stream.readPayload(av.int(2) ?: 0, null) } // avatar
            f.msg(8)?.let { s -> stream.readPayload(s.int(2) ?: 0, null) } // sticker
        }

        onProgress(Progress(frames, msgs.size, partFiles.size, "Сохраняю сообщения"))
        return save(recipients, threads, groupKeys, msgs, parts, partFiles, tmpDir, onProgress)
    }

    // ---------------- SQL statements ----------------

    private val createRe = Regex("""^CREATE TABLE\s+"?(\w+)"?\s*\((.*)\)\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val insertRe = Regex("""^INSERT INTO\s+"?(\w+)"?\s+VALUES""", RegexOption.IGNORE_CASE)

    private fun columnsOf(defs: String): List<String> {
        val out = ArrayList<String>(); var depth = 0; val cur = StringBuilder()
        for (ch in defs) {
            when (ch) { '(' -> depth++; ')' -> depth-- }
            if (ch == ',' && depth == 0) { out += cur.toString(); cur.clear() } else cur.append(ch)
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out.map { it.trim() }
            .filterNot { it.uppercase().let { u -> u.startsWith("UNIQUE") || u.startsWith("PRIMARY KEY") || u.startsWith("FOREIGN KEY") || u.startsWith("CONSTRAINT") || u.startsWith("CHECK") } }
            .map { it.split(Regex("\\s+"))[0].trim('"', '`', '[', ']').lowercase() }
    }

    private fun params(st: ProtoMessage): List<Any?> = st.all(2).map { f ->
        val p = f.asMessage()
        when {
            p.has(1) -> p.string(1)
            p.has(2) -> p.long(2)
            p.has(3) -> java.lang.Double.longBitsToDouble(p.long(3) ?: 0)
            p.has(4) -> p.bytes(4)
            else -> null
        }
    }

    private fun handleStatement(
        st: ProtoMessage, tables: HashMap<String, Table>, recipients: HashMap<Long, Recipient>, threads: HashMap<Long, Long>,
        groupKeys: HashMap<String, Pair<ByteArray, String?>>, msgs: ArrayList<RawMsg>, parts: HashMap<Long, Part>,
    ) {
        val sql = st.string(1)?.trim() ?: return
        createRe.find(sql)?.let { m -> tables[m.groupValues[1].lowercase()] = Table(columnsOf(m.groupValues[2])); return }
        val ins = insertRe.find(sql) ?: return
        val name = ins.groupValues[1].lowercase()
        val t = tables[name] ?: return
        val v = params(st)
        fun s(vararg c: String) = t.idx(*c).let { if (it >= 0) v.getOrNull(it) else null }
        fun str(vararg c: String) = (s(*c) as? String)?.takeIf { it.isNotBlank() }
        fun lng(vararg c: String) = when (val x = s(*c)) { is Long -> x; is String -> x.toLongOrNull(); is Double -> x.toLong(); else -> null }
        when (name) {
            "recipient" -> {
                val id = lng("_id") ?: return
                val given = str("profile_given_name"); val family = str("profile_family_name")
                val profileName = str("profile_joined_name") ?: listOfNotNull(given, family).joinToString(" ").takeIf { it.isNotBlank() }
                val pk = when (val x = s("profile_key")) {
                    is String -> runCatching { java.util.Base64.getDecoder().decode(x) }.getOrNull()
                    is ByteArray -> x; else -> null
                }
                recipients[id] = Recipient(str("uuid", "aci", "service_id"), str("phone", "e164"), str("group_id"),
                    str("system_display_name", "system_joined_name", "system_given_name") ?: profileName ?: str("signal_profile_name"), pk)
            }
            "thread" -> { val id = lng("_id") ?: return; threads[id] = lng("thread_recipient_id", "recipient_id", "recipient_ids") ?: return }
            "groups" -> {
                val gid = str("group_id") ?: return
                val mk = s("master_key") as? ByteArray ?: return
                groupKeys[gid] = mk to str("title")
            }
            "sms", "mms", "message" -> {
                val mms = name != "sms"
                msgs += RawMsg(
                    id = lng("_id") ?: return, mms = mms,
                    thread = lng("thread_id") ?: return,
                    recipient = lng("from_recipient_id", "recipient_id", "address") ?: 0,
                    sentTs = lng("date_sent") ?: lng("date") ?: 0,
                    type = lng("type", "msg_box") ?: 0,
                    body = str("body"),
                    quoteTs = lng("quote_id"), quoteAuthor = lng("quote_author"), quoteBody = str("quote_body"),
                )
            }
            "part", "attachment" -> {
                val id = lng("_id") ?: return
                parts[id] = Part(lng("mid", "message_id") ?: return, str("ct", "content_type"), str("file_name"),
                    lng("width")?.toInt(), lng("height")?.toInt())
            }
        }
    }

    // ---------------- mapping to our DB ----------------

    private fun isOutgoing(type: Long): Boolean = (type and 0x1F) in setOf(2L, 11L, 21L, 22L, 23L, 24L, 25L, 26L)
    private fun isRegular(type: Long): Boolean {
        val base = type and 0x1F
        if (base !in setOf(20L, 21L, 22L, 23L, 24L, 25L, 26L)) return false
        // group update, quit, expiration timer, GV2 update, key exchange markers
        return type and (0x10000L or 0x20000L or 0x40000L or 0x80000L or 0x8000L) == 0L
    }

    private fun save(
        recipients: Map<Long, Recipient>, threads: Map<Long, Long>, groupKeys: Map<String, Pair<ByteArray, String?>>,
        msgs: List<RawMsg>, parts: Map<Long, Part>, partFiles: Map<Long, File>, tmpDir: File, onProgress: (Progress) -> Unit,
    ): Result {
        val repo = core.repo
        val self = core.account.aci
        val convFor = HashMap<Long, String?>()
        val db = core.db.writableDatabase

        // contacts first, so conversation titles resolve
        recipients.values.forEach { r ->
            val u = r.uuid?.lowercase() ?: return@forEach
            if (r.groupId == null && u != self) repo.upsertContact(u, e164 = r.phone, systemName = r.name, profileKey = r.profileKey?.takeIf { it.size == 32 })
        }

        fun conversation(threadId: Long): String? = convFor.getOrPut(threadId) {
            val rid = threads[threadId] ?: return@getOrPut null
            val r = recipients[rid] ?: return@getOrPut null
            when {
                r.groupId != null -> {
                    val gk = groupKeys[r.groupId] ?: return@getOrPut null
                    val gid = runCatching {
                        Hex.toStringCondensed(GroupSecretParams.deriveFromMasterKey(GroupMasterKey(gk.first)).publicParams.groupIdentifier.serialize())
                    }.getOrNull() ?: return@getOrPut null
                    repo.ensureGroup(gid, gk.first).also { c -> gk.second?.let { repo.renameConversation(c.id, it) } }.id
                }
                r.uuid != null -> repo.ensureDirect(r.uuid.lowercase(), self).id
                else -> null
            }
        }

        val partsByMsg = parts.entries.groupBy({ it.value.mid }, { it.key to it.value })
        var imported = 0; var skipped = 0; var att = 0
        val convs = HashSet<String>()
        db.beginTransaction()
        try {
            msgs.sortedBy { it.sentTs }.forEachIndexed { i, m ->
                if (i % 500 == 0) onProgress(Progress(0, imported, att, "Сохраняю сообщения: $imported"))
                if (!isRegular(m.type)) { skipped++; return@forEachIndexed }
                val conv = conversation(m.thread) ?: run { skipped++; return@forEachIndexed }
                val out = isOutgoing(m.type)
                val author = if (out) self else recipients[m.recipient]?.uuid?.lowercase()
                val myParts = if (m.mms) partsByMsg[m.id].orEmpty() else emptyList()
                val infos = myParts.map { (rowId, p) -> AttachmentInfo(p.contentType, p.fileName, partFiles[rowId]?.length()?.toInt(), null, p.width, p.height, null) }
                if (m.body.isNullOrBlank() && infos.isEmpty()) { skipped++; return@forEachIndexed }
                val qa = m.quoteAuthor?.let { recipients[it]?.uuid?.lowercase() }
                val id = repo.insertMessage(conv, author, out, m.body, m.sentTs, if (out) MsgStatus.SENT else 11, infos,
                    quoteTs = m.quoteTs?.takeIf { it > 0 }, quoteAuthor = qa, quoteText = m.quoteBody, countUnread = false)
                if (id < 0) { skipped++; return@forEachIndexed }
                imported++; convs += conv
                myParts.forEachIndexed { idx, (rowId, p) ->
                    val src = partFiles[rowId] ?: return@forEachIndexed
                    val ext = when { p.contentType == "image/png" -> "png"; p.contentType?.startsWith("audio/") == true -> "m4a"; else -> "jpg" }
                    val dst = File(core.context.filesDir, "attachments/${id}_$idx.$ext").apply { parentFile?.mkdirs() }
                    if (src.renameTo(dst) || runCatching { src.copyTo(dst, true); src.delete() }.isSuccess) {
                        repo.updateAttachmentPath(id, idx, dst.absolutePath); att++
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            tmpDir.deleteRecursively()
        }
        core.db.notifyChanged()
        Log.i("BackupImporter", "imported=$imported skipped=$skipped attachments=$att convs=${convs.size}")
        return Result(imported, convs.size, att, skipped)
    }
}
