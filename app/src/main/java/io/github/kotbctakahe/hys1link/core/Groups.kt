package io.github.kotbctakahe.hys1link.core

import android.util.Log
import io.github.kotbctakahe.hys1link.LinkConfig
import io.github.kotbctakahe.hys1link.crypto.B64
import io.github.kotbctakahe.hys1link.net.ApiException
import io.github.kotbctakahe.hys1link.proto.Content
import io.github.kotbctakahe.hys1link.proto.ProtoMessage
import io.github.kotbctakahe.hys1link.store.Conversation
import io.github.kotbctakahe.hys1link.store.MsgStatus
import okhttp3.Credentials
import okhttp3.Request
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

data class GroupMember(val uuid: String, val profileKey: ByteArray?)
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

    fun refreshTitleAsync(conv: Conversation) {
        val last = core.repo.db.getLong("group_ts:${conv.id}")
        if (System.currentTimeMillis() - last < 6 * 3600_000L && cached(conv.id) != null) return
        if (!inFlight.add(conv.id)) return
        core.scope.launchIo {
            try { refresh(conv) } catch (e: Exception) { Log.w(TAG, "group refresh failed", e) } finally { inFlight.remove(conv.id) }
        }
    }

    fun refresh(conv: Conversation): GroupState {
        val mk = conv.groupMasterKey ?: error("No master key")
        val secret = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(mk))
        val cred = credentialForToday()
        val presentation = ClientZkAuthOperations(serverParams).createAuthCredentialPresentation(SecureRandom(), secret, cred)
        val auth = Credentials.basic(
            Hex.toStringCondensed(secret.publicParams.serialize()),
            Hex.toStringCondensed(presentation.serialize())
        )
        val bytes = core.http.newCall(
            Request.Builder().url("${LinkConfig.STORAGE_URL}/v1/groups/").header("Authorization", auth).get().build()
        ).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, r.body?.string())
            r.body!!.bytes()
        }
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
                GroupMember(uuid.toString(), pk)
            }.getOrNull()
        }
        val state = GroupState(title, g.int(6) ?: 0, members, description)
        core.repo.db.putString("group:${conv.id}", serialize(state))
        core.repo.db.putLong("group_ts:${conv.id}", System.currentTimeMillis())
        core.repo.renameConversation(conv.id, title)
        members.forEach { m ->
            if (m.uuid != core.account.aci && core.repo.contact(m.uuid)?.profileKey == null) {
                core.repo.upsertContact(m.uuid, profileKey = m.profileKey)
                if (m.profileKey != null) core.profiles.refreshAsync(m.uuid)
            }
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

    /** Sends a text to every member individually (Signal "legacy" group fan-out). */
    fun sendText(conv: Conversation, body: String) {
        val self = core.account.aci ?: return
        val state = cached(conv.id) ?: refresh(conv)
        val ts = System.currentTimeMillis()
        val msgId = core.repo.insertMessage(conv.id, self, true, body, ts, MsgStatus.PENDING)
        val mk = conv.groupMasterKey!!
        var delivered = 0
        state.members.filter { it.uuid != self }.forEach { m ->
            runCatching {
                core.sender.sendContent(m.uuid, Content.withData(Content.dataMessage(body, ts, core.account.profileKey, mk, state.revision)), ts)
                delivered++
            }.onFailure { Log.w(TAG, "group send to ${m.uuid} failed", it) }
        }
        runCatching {
            core.sender.sendContent(self, Content.syncSent(null, ts, Content.dataMessage(body, ts, core.account.profileKey, mk, state.revision)), ts, urgent = false)
        }
        core.repo.setStatus(msgId, if (delivered > 0 || state.members.size <= 1) MsgStatus.SENT else MsgStatus.FAILED)
    }

    private fun serialize(s: GroupState) = JSONObject()
        .put("title", s.title).put("rev", s.revision).put("desc", s.description)
        .put("members", JSONArray().apply { s.members.forEach { put(JSONObject().put("u", it.uuid).put("pk", it.profileKey?.let(B64::enc))) } })
        .toString()

    private fun parse(t: String): GroupState? = runCatching {
        val o = JSONObject(t)
        val arr = o.getJSONArray("members")
        GroupState(o.getString("title"), o.getInt("rev"),
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { m -> GroupMember(m.getString("u"), m.optString("pk").takeIf { it.isNotBlank() && it != "null" }?.let(B64::dec)) } },
            o.optString("desc").takeIf { it.isNotBlank() && it != "null" })
    }.getOrNull()

    companion object { private const val TAG = "Groups" }
}
