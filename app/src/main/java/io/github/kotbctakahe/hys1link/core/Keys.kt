package io.github.kotbctakahe.hys1link.core

import android.util.Log
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/** Generates and uploads one-time and signed pre-keys for this device (ACI identity). */
class Keys(private val core: Core) {
    private val batch = 100
    private val minCount = 15
    private val rotateEvery = 2L * 24 * 60 * 60 * 1000

    @Synchronized
    fun refreshPreKeys(force: Boolean = false) {
        val acct = core.account
        val store = core.store ?: return
        val identity = acct.identityKeyPair ?: return
        val serverCount = if (force) 0 else runCatching { core.api.preKeyCount() }.getOrElse { Log.w(TAG, "count failed", it); return }
        val needRotate = System.currentTimeMillis() - acct.lastSignedPreKeyRotation > rotateEvery
        if (!force && serverCount >= minCount && !needRotate) return

        var next = acct.nextPreKeyId
        val preKeys = (0 until batch).map {
            val id = next
            next = if (next >= 0xFFFFFE) 1 else next + 1
            PreKeyRecord(id, Curve.generateKeyPair()).also { store.storePreKey(id, it) }
        }
        acct.nextPreKeyId = next

        val signedId = acct.nextSignedPreKeyId
        val kp = Curve.generateKeyPair()
        val sig = Curve.calculateSignature(identity.privateKey, kp.publicKey.serialize())
        val signed = SignedPreKeyRecord(signedId, System.currentTimeMillis(), kp, sig)
        store.storeSignedPreKey(signedId, signed)
        acct.nextSignedPreKeyId = if (signedId >= 0xFFFFFE) 1 else signedId + 1

        core.api.registerPreKeys(identity.publicKey, signed, preKeys)
        acct.lastSignedPreKeyRotation = System.currentTimeMillis()

        // keep the previous signed pre-key for late messages, drop older ones
        store.loadSignedPreKeys().sortedByDescending { it.timestamp }.drop(2).forEach { store.removeSignedPreKey(it.id) }
        Log.i(TAG, "uploaded ${preKeys.size} prekeys, signed=$signedId")
    }

    companion object { private const val TAG = "Keys" }
}
