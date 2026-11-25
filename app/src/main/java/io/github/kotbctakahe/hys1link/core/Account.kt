package io.github.kotbctakahe.hys1link.core

import io.github.kotbctakahe.hys1link.net.Creds
import io.github.kotbctakahe.hys1link.store.Db
import org.signal.libsignal.protocol.IdentityKeyPair

/** Persisted state of the linked device. */
class Account(private val db: Db) {
    val isLinked: Boolean get() = aci != null && password != null && deviceId > 0

    var aci: String?
        get() = db.getString("aci")
        set(v) = db.putString("aci", v)
    var pni: String?
        get() = db.getString("pni")
        set(v) = db.putString("pni", v)
    var e164: String?
        get() = db.getString("e164")
        set(v) = db.putString("e164", v)
    var password: String?
        get() = db.getString("password")
        set(v) = db.putString("password", v)
    var deviceId: Int
        get() = db.getLong("device_id", 0).toInt()
        set(v) = db.putLong("device_id", v.toLong())
    var registrationId: Int
        get() = db.getLong("registration_id", 0).toInt()
        set(v) = db.putLong("registration_id", v.toLong())
    var profileKey: ByteArray?
        get() = db.getBlob("profile_key")
        set(v) = db.putBlob("profile_key", v)
    var identityKeyPair: IdentityKeyPair?
        get() = db.getBlob("aci_identity")?.let { IdentityKeyPair(it) }
        set(v) = db.putBlob("aci_identity", v?.serialize())
    var pniIdentityKeyPair: IdentityKeyPair?
        get() = db.getBlob("pni_identity")?.let { IdentityKeyPair(it) }
        set(v) = db.putBlob("pni_identity", v?.serialize())
    var nextPreKeyId: Int
        get() = db.getLong("next_prekey_id", 1).toInt()
        set(v) = db.putLong("next_prekey_id", v.toLong())
    var nextSignedPreKeyId: Int
        get() = db.getLong("next_signed_prekey_id", 1).toInt()
        set(v) = db.putLong("next_signed_prekey_id", v.toLong())
    var lastSignedPreKeyRotation: Long
        get() = db.getLong("signed_prekey_rotation", 0)
        set(v) = db.putLong("signed_prekey_rotation", v)
    var lastContactSync: Long
        get() = db.getLong("last_contact_sync", 0)
        set(v) = db.putLong("last_contact_sync", v)
    var sendReadReceipts: Boolean
        get() = db.getLong("read_receipts", 1) == 1L
        set(v) = db.putLong("read_receipts", if (v) 1 else 0)
    var themeMode: Int // 0 system, 1 light, 2 dark
        get() = db.getLong("theme_mode", 0).toInt()
        set(v) = db.putLong("theme_mode", v.toLong())
    var dynamicColor: Boolean
        get() = db.getLong("dynamic_color", 0) == 1L
        set(v) = db.putLong("dynamic_color", if (v) 1 else 0)
    var accent: Int // palette index
        get() = db.getLong("accent", 0).toInt()
        set(v) = db.putLong("accent", v.toLong())

    fun creds(): Creds? {
        val a = aci ?: return null
        val p = password ?: return null
        return Creds(if (deviceId == 1) a else "$a.$deviceId", p)
    }
}
