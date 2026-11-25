package io.github.kotbctakahe.hys1link.core

import android.util.Log
import io.github.kotbctakahe.hys1link.LinkConfig
import io.github.kotbctakahe.hys1link.crypto.B64
import io.github.kotbctakahe.hys1link.net.ApiException
import io.github.kotbctakahe.hys1link.proto.ProtoMessage
import io.github.kotbctakahe.hys1link.store.Conversation
import io.github.kotbctakahe.hys1link.store.MsgStatus
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import io.github.kotbctakahe.hys1link.proto.ProtoWriter
import org.signal.libsignal.zkgroup.profiles.ClientZkProfileOperations
import org.signal.libsignal.zkgroup.profiles.ExpiringProfileKeyCredentialResponse
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.util.Hex
import org.signal.libsignal.zkgroup.ServerPublicParams
import org.signal.libsignal.zkgroup.auth.AuthCredentialWithPni
import org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse
import org.signal.libsignal.zkgroup.auth.ClientZkAuthOperations
import org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.libsignal.zkgroup.groups.ProfileKeyCiphertext
import org.signal.libsignal.zkgroup.groups.UuidCiphertext
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class GroupMember(val uuid: String, val profileKey: ByteArray?, val role: Int = 1)
data class GroupState(val title: String, val revision: Int, val members: List<GroupMember>, val description: String?)

/**
 * Groups V2 over Link's storage service (stg.linkmessenger.me):
 * zk auth credential -> presentation -> GET /v1/groups/ -> decrypt title & member list.
 */
class Groups(private val core: Core) {
    private val serverParams by lazy { ServerPublicParams(B64.dec(LinkConfig.ZKGROUP_SERVER_PUBLIC_PARAMS)) }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val day = 86_400L

    fun cached(convId: String): GroupState? = core.repo.db.getString("group:$convId")?.let { parse(it) }

    fun refreshAll() { core.repo.groupConversations().forEach { refreshTitleAsync(it) } }

    fun refreshTitleAsync(conv: Conversation, force: Boolean = false) {
        val last = core.repo.db.getLong("group_ts:${conv.id}")
        if (!force && System.currentTimeMillis() - last < 6 * 3600_000L && cached(conv.id) != null) return
        if (!inFlight.add(conv.id)) return
        core.scope.launchIo {
            try { refresh(conv) } catch (e: Exception) { Log.w(TAG, "group refresh failed", e) } finally { inFlight.remove(conv.id) }
        }
    }

    private fun groupAuth(secret: GroupSecretParams): String {
        val presentation = ClientZkAuthOperations(serverParams).createAuthCredentialPresentation(SecureRandom(), secret, credentialForToday())
        return Credentials.basic(Hex.toStringCondensed(secret.publicParams.serialize()), Hex.toStringCondensed(presentation.serialize()))
    }

    private fun storage(method: String, secret: GroupSecretParams, body: ByteArray?): ByteArray {
        val rb = body?.toRequestBody("application/x-protobuf".toMediaType())
        val req = Request.Builder().url("${LinkConfig.STORAGE_URL}/v1/groups/").header("Authorization", groupAuth(secret))
            .method(method, rb).build()
        return core.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, r.body?.string()?.take(300))
            r.body?.bytes() ?: ByteArray(0)
        }
    }

    fun refresh(conv: Conversation): GroupState {
        val mk = conv.groupMasterKey ?: error("No master key")
        val secret = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(mk))
        val bytes = storage("GET", secret, null)
        val g = ProtoMessage.parse(bytes)
        val cipher = ClientZkGroupCipher(secret)
        fun blob(field: Int): ProtoMessage? = g.bytes(field)?.takeIf { it.isNotEmpty() }?.let {
            runCatching { ProtoMessage.parse(cipher.decryptBlob(it)) }.getOrNull()
        }
        val title = blob(2)?.string(1)?.takeIf { it.isNotBlank() } ?: "Группа без названия"
        val description = blob(11)?.string(4)
        val members = g.all(7).mapNotNull { f ->
            val m = f.asMessage()
            runCatching {
                val uuid = cipher.decryptUuid(UuidCiphertext(m.bytes(1)!!))
                val pk = m.bytes(3)?.takeIf { it.isNotEmpty() }?.let {
                    runCatching { cipher.decryptProfileKey(ProfileKeyCiphertext(it), uuid).serialize() }.getOrNull()
                }
                GroupMember(uuid.toString(), pk, m.int(2) ?: 1)
            }.getOrNull()
        }
        // Group avatar: CDN path at tag 3, content = encryptBlob(GroupAttributeBlob{2: avatar bytes})
        g.string(3)?.takeIf { it.isNotBlank() }?.let { path ->
            if (core.repo.db.getString("group_avatar_src:${conv.id}") != path) runCatching {
                val enc = core.api.downloadProfileAvatar(path)
                val img = ProtoMessage.parse(cipher.decryptBlob(enc)).bytes(2) ?: error("empty avatar blob")
                val f = java.io.File(core.context.filesDir, "avatars/group_${conv.id.removePrefix("g:")}.jpg").apply { parentFile?.mkdirs() }
                f.writeBytes(img)
                core.repo.setConversationAvatar(conv.id, f.absolutePath)
                core.repo.db.putString("group_avatar_src:${conv.id}", path)
            }.onFailure { Log.w(TAG, "group avatar failed", it) }
        }
        val state = GroupState(title, g.int(6) ?: 0, members, description)
        core.repo.db.putString("group:${conv.id}", serialize(state))
        core.repo.db.putLong("group_ts:${conv.id}", System.currentTimeMillis())
        core.repo.renameConversation(conv.id, title)
        members.forEach { m ->
            if (m.uuid == core.account.aci) return@forEach
            val known = core.repo.contact(m.uuid)
            if (known?.profileKey == null && m.profileKey != null) core.repo.upsertContact(m.uuid, profileKey = m.profileKey)
            else if (known == null) core.repo.upsertContact(m.uuid)
            if ((known?.profileKey ?: m.profileKey) != null && (known?.avatarPath == null || known.profileName == null)) core.profiles.refreshAsync(m.uuid)
        }
        return state
    }

    private fun credentialForToday(): AuthCredentialWithPni {
        val now = System.currentTimeMillis() / 1000
        val today = now - now % day
        core.repo.db.getString("gcred:$today")?.let { return AuthCredentialWithPni(B64.dec(it)) }

        val aci = UUID.fromString(core.account.aci)
        val pni = UUID.fromString(core.account.pni ?: error("PNI неизвестен — групповые учётки недоступны"))
        val text = core.http.newCall(
            Request.Builder()
                .url("${LinkConfig.SERVICE_URL}/v1/certificate/auth/group?redemptionStartSeconds=$today&redemptionEndSeconds=${today + 7 * day}")
                .header("Authorization", core.account.creds()!!.let { Credentials.basic(it.username, it.password) })
                .get().build()
        ).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, r.body?.string())
            r.body!!.string()
        }
        val arr = JSONObject(text).getJSONArray("credentials")
        val ops = ClientZkAuthOperations(serverParams)
        var result: AuthCredentialWithPni? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.getLong("redemptionTime")
            val c = ops.receiveAuthCredentialWithPni(aci, pni, t, AuthCredentialWithPniResponse(B64.dec(o.getString("credential"))))
            core.repo.db.putString("gcred:$t", B64.enc(c.serialize()))
            if (t == today) result = c
        }
        return result ?: error("Сервер не выдал групповую учётку на сегодня")
    }

    // ===================== management =====================

    /** Expiring profile-key credential → presentation for adding [uuid] to a group. */
    private fun presentation(secret: GroupSecretParams, uuid: String, profileKey: ByteArray): ByteArray {
        val ops = ClientZkProfileOperations(serverParams)
        val u = UUID.fromString(uuid)
        val pk = ProfileKey(profileKey)
        val ctx = ops.createProfileKeyCredentialRequestContext(SecureRandom(), u, pk)
        val version = pk.getProfileKeyVersion(u).serialize()
        val path = "/v1/profile/$uuid/$version/${Hex.toStringCondensed(ctx.request.serialize())}?credentialType=expiringProfileKey"
        val c = core.account.creds()!!
        val text = core.http.newCall(Request.Builder().url(LinkConfig.SERVICE_URL + path)
            .header("Authorization", Credentials.basic(c.username, c.password)).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, r.body?.string())
            r.body!!.string()
        }
        val cred = JSONObject(text).optString("credential").takeIf { it.isNotBlank() } ?: error("Нет учётки профиля для $uuid")
        val expiring = ops.receiveExpiringProfileKeyCredential(ctx, ExpiringProfileKeyCredentialResponse(B64.dec(cred)))
        return ops.createProfileKeyCredentialPresentation(SecureRandom(), secret, expiring).serialize()
    }

    private fun blob(cipher: ClientZkGroupCipher, field: Int, value: String): ByteArray =
        cipher.encryptBlob(ProtoWriter().string(field, value).toByteArray())

    data class CreateResult(val convId: String, val skipped: List<String>)

    /** Creates a Groups V2 group with the given contacts (who have profile keys). */
    fun create(title: String, memberUuids: List<String>): CreateResult {
        val self = core.account.aci!!
        val selfKey = core.account.profileKey ?: error("Нет ключа профиля аккаунта")
        val secret = GroupSecretParams.generate()
        val cipher = ClientZkGroupCipher(secret)
        val skipped = ArrayList<String>()
        val g = ProtoWriter()
            .bytes(1, secret.publicParams.serialize())
            .bytes(2, blob(cipher, 1, title))
            .message(5, ProtoWriter().uint(1, 2).uint(2, 2).uint(3, 4)) // attributes=MEMBER, members=MEMBER, invite link=UNSATISFIABLE
            .uint(6, 0)
            .message(7, ProtoWriter().uint(2, 2).bytes(4, presentation(secret, self, selfKey))) // self = ADMINISTRATOR
        memberUuids.distinct().filter { it != self }.forEach { u ->
            val pk = core.repo.contact(u)?.profileKey
            if (pk == null) { skipped += u; return@forEach }
            runCatching { presentation(secret, u, pk) }
                .onSuccess { g.message(7, ProtoWriter().uint(2, 1).bytes(4, it)) }
                .onFailure { Log.w(TAG, "no credential for $u", it); skipped += u }
        }
        storage("PUT", secret, g.toByteArray())
        val mk = secret.masterKey.serialize()
        val gid = Hex.toStringCondensed(secret.publicParams.groupIdentifier.serialize())
        val conv = core.repo.ensureGroup(gid, mk)
        core.repo.renameConversation(conv.id, title)
        val state = refresh(core.repo.conversation(conv.id)!!)
        notifyMembers(conv.id, mk, state.revision, null, state.members.map { it.uuid })
        core.repo.insertSystem(conv.id, "Вы создали группу «$title»")
        return CreateResult(conv.id, skipped)
    }

    /** Applies GroupChange.Actions (revision = current + 1), then tells members (incl. [alsoNotify]). */
    private fun change(conv: Conversation, alsoNotify: List<String> = emptyList(), build: (ProtoWriter, ClientZkGroupCipher, GroupSecretParams) -> Unit) {
        val mk = conv.groupMasterKey ?: error("Не группа")
        val secret = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(mk))
        val cipher = ClientZkGroupCipher(secret)
        val current = refresh(conv)
        val actions = ProtoWriter().uint(2, current.revision + 1)
        build(actions, cipher, secret)
        val signedChange = storage("PATCH", secret, actions.toByteArray())
        val after = refresh(core.repo.conversation(conv.id)!!)
        notifyMembers(conv.id, mk, after.revision, signedChange, (after.members.map { it.uuid } + alsoNotify).distinct())
    }

    private fun notifyMembers(convId: String, mk: ByteArray, revision: Int, groupChange: ByteArray?, members: List<String>) {
        val self = core.account.aci!!
        val ts = System.currentTimeMillis()
        fun dm() = io.github.kotbctakahe.hys1link.proto.Content.dataMessage(null, ts, core.account.profileKey, mk, revision, groupChange = groupChange)
        members.filter { it != self }.forEach { u -> runCatching { core.sender.sendContent(u, io.github.kotbctakahe.hys1link.proto.Content.withData(dm()), ts) } }
        runCatching { core.sender.sendContent(self, io.github.kotbctakahe.hys1link.proto.Content.syncSent(null, ts, dm()), ts, urgent = false) }
    }

    fun rename(conv: Conversation, title: String) =
        change(conv) { a, cipher, _ -> a.message(10, ProtoWriter().bytes(1, blob(cipher, 1, title))) }.also { core.repo.insertSystem(conv.id, "Вы переименовали группу в «$title»") }

    fun setDescription(conv: Conversation, text: String) =
        change(conv) { a, cipher, _ -> a.message(20, ProtoWriter().bytes(1, blob(cipher, 4, text))) }

    fun addMember(conv: Conversation, uuid: String) {
        val pk = core.repo.contact(uuid)?.profileKey ?: error("Нет ключа профиля этого контакта — пусть сначала напишет вам")
        change(conv) { a, _, secret -> a.message(3, ProtoWriter().message(1, ProtoWriter().uint(2, 1).bytes(4, presentation(secret, uuid, pk)))) }
        core.repo.insertSystem(conv.id, "Вы добавили ${core.repo.contact(uuid)?.displayName ?: "участника"}")
    }

    fun removeMember(conv: Conversation, uuid: String) {
        change(conv, alsoNotify = listOf(uuid)) { a, cipher, _ -> a.message(4, ProtoWriter().bytes(1, cipher.encryptUuid(UUID.fromString(uuid)).serialize())) }
        core.repo.insertSystem(conv.id, "Вы удалили ${core.repo.contact(uuid)?.displayName ?: "участника"}")
    }

    fun makeAdmin(conv: Conversation, uuid: String, admin: Boolean) =
        change(conv) { a, cipher, _ -> a.message(5, ProtoWriter().bytes(1, cipher.encryptUuid(UUID.fromString(uuid)).serialize()).uint(2, if (admin) 2 else 1)) }

    fun leave(conv: Conversation) {
        val self = core.account.aci!!
        val before = cached(conv.id)?.members?.map { it.uuid }.orEmpty()
        val mk = conv.groupMasterKey ?: return
        val secret = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(mk))
        val cipher = ClientZkGroupCipher(secret)
        val current = refresh(conv)
        val actions = ProtoWriter().uint(2, current.revision + 1).message(4, ProtoWriter().bytes(1, cipher.encryptUuid(UUID.fromString(self)).serialize()))
        val signed = storage("PATCH", secret, actions.toByteArray())
        notifyMembers(conv.id, mk, current.revision + 1, signed, before)
        core.repo.insertSystem(conv.id, "Вы вышли из группы")
        core.repo.db.putString("group_left:${conv.id}", "1")
    }

    fun isAdmin(convId: String, uuid: String?): Boolean = cached(convId)?.members?.firstOrNull { it.uuid == uuid }?.role == 2

    private fun serialize(s: GroupState) = JSONObject()
        .put("title", s.title).put("rev", s.revision).put("desc", s.description)
        .put("members", JSONArray().apply { s.members.forEach { put(JSONObject().put("u", it.uuid).put("pk", it.profileKey?.let(B64::enc)).put("r", it.role)) } })
        .toString()

    private fun parse(t: String): GroupState? = runCatching {
        val o = JSONObject(t)
        val arr = o.getJSONArray("members")
        GroupState(o.getString("title"), o.getInt("rev"),
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { m -> GroupMember(m.getString("u"), m.optString("pk").takeIf { it.isNotBlank() && it != "null" }?.let(B64::dec), m.optInt("r", 1)) } },
            o.optString("desc").takeIf { it.isNotBlank() && it != "null" })
    }.getOrNull()

    companion object { private const val TAG = "Groups" }
}
