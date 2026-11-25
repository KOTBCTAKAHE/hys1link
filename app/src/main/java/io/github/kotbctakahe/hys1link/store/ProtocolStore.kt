package io.github.kotbctakahe.hys1link.store

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * SQLite-backed Signal protocol store. Identity trust policy is TOFU with automatic
 * acceptance of changed keys (same default behaviour as Signal/Link for non-verified contacts).
 */
class ProtocolStore(
    private val db: Db,
    private val identityKeyPair: IdentityKeyPair,
    private val registrationId: Int,
) : SignalProtocolStore {

    private val w get() = db.writableDatabase
    private val r get() = db.readableDatabase

    @Synchronized override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair
    @Synchronized override fun getLocalRegistrationId(): Int = registrationId

    @Synchronized
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val existing = getIdentity(address)
        if (existing == identityKey) return false
        w.insertWithOnConflict("identities", null, ContentValues().apply {
            put("address", address.name); put("key", identityKey.serialize()); put("ts", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return existing != null
    }

    @Synchronized
    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean = true

    @Synchronized
    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        r.rawQuery("SELECT key FROM identities WHERE address=?", arrayOf(address.name)).use {
            if (it.moveToFirst()) IdentityKey(it.getBlob(0), 0) else null
        }

    // ---- one-time prekeys ----
    @Synchronized
    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        r.rawQuery("SELECT record FROM prekeys WHERE id=?", arrayOf(preKeyId.toString())).use {
            if (it.moveToFirst()) PreKeyRecord(it.getBlob(0)) else throw InvalidKeyIdException("No prekey $preKeyId")
        }

    @Synchronized
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        w.insertWithOnConflict("prekeys", null, ContentValues().apply { put("id", preKeyId); put("record", record.serialize()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized override fun containsPreKey(preKeyId: Int): Boolean =
        r.rawQuery("SELECT 1 FROM prekeys WHERE id=?", arrayOf(preKeyId.toString())).use { it.moveToFirst() }

    @Synchronized override fun removePreKey(preKeyId: Int) { w.delete("prekeys", "id=?", arrayOf(preKeyId.toString())) }

    // ---- signed prekeys ----
    @Synchronized
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        r.rawQuery("SELECT record FROM signed_prekeys WHERE id=?", arrayOf(signedPreKeyId.toString())).use {
            if (it.moveToFirst()) SignedPreKeyRecord(it.getBlob(0)) else throw InvalidKeyIdException("No signed prekey $signedPreKeyId")
        }

    @Synchronized
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        r.rawQuery("SELECT record FROM signed_prekeys", null).use { c ->
            buildList { while (c.moveToNext()) add(SignedPreKeyRecord(c.getBlob(0))) }
        }

    @Synchronized
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        w.insertWithOnConflict("signed_prekeys", null, ContentValues().apply { put("id", signedPreKeyId); put("record", record.serialize()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        r.rawQuery("SELECT 1 FROM signed_prekeys WHERE id=?", arrayOf(signedPreKeyId.toString())).use { it.moveToFirst() }

    @Synchronized override fun removeSignedPreKey(signedPreKeyId: Int) { w.delete("signed_prekeys", "id=?", arrayOf(signedPreKeyId.toString())) }

    // ---- sessions ----
    @Synchronized
    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        r.rawQuery("SELECT record FROM sessions WHERE name=? AND device=?", arrayOf(address.name, address.deviceId.toString())).use {
            if (it.moveToFirst()) SessionRecord(it.getBlob(0)) else SessionRecord()
        }

    @Synchronized
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { a ->
            r.rawQuery("SELECT record FROM sessions WHERE name=? AND device=?", arrayOf(a.name, a.deviceId.toString())).use {
                if (it.moveToFirst()) SessionRecord(it.getBlob(0)) else throw NoSessionException("No session for $a")
            }
        }

    @Synchronized
    override fun getSubDeviceSessions(name: String): List<Int> =
        r.rawQuery("SELECT device FROM sessions WHERE name=? AND device<>1", arrayOf(name)).use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0)) }
        }

    /** All devices (including 1) with an existing session. */
    @Synchronized
    fun devicesWithSession(name: String): List<Int> =
        r.rawQuery("SELECT device FROM sessions WHERE name=?", arrayOf(name)).use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0)) }
        }

    @Synchronized
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        w.insertWithOnConflict("sessions", null, ContentValues().apply {
            put("name", address.name); put("device", address.deviceId); put("record", record.serialize())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized override fun containsSession(address: SignalProtocolAddress): Boolean =
        r.rawQuery("SELECT record FROM sessions WHERE name=? AND device=?", arrayOf(address.name, address.deviceId.toString())).use {
            it.moveToFirst() && runCatching { SessionRecord(it.getBlob(0)).hasSenderChain() }.getOrDefault(false)
        }

    @Synchronized override fun deleteSession(address: SignalProtocolAddress) {
        w.delete("sessions", "name=? AND device=?", arrayOf(address.name, address.deviceId.toString()))
    }

    @Synchronized override fun deleteAllSessions(name: String) { w.delete("sessions", "name=?", arrayOf(name)) }

    // ---- sender keys ----
    @Synchronized
    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        w.insertWithOnConflict("sender_keys", null, ContentValues().apply {
            put("address", "${sender.name}.${sender.deviceId}"); put("distribution", distributionId.toString()); put("record", record.serialize())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        r.rawQuery("SELECT record FROM sender_keys WHERE address=? AND distribution=?",
            arrayOf("${sender.name}.${sender.deviceId}", distributionId.toString())).use {
            if (it.moveToFirst()) SenderKeyRecord(it.getBlob(0)) else null
        }

    fun preKeyCount(): Int = r.rawQuery("SELECT COUNT(*) FROM prekeys", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
