package io.github.kotbctakahe.hys1link.core

import android.util.Log
import io.github.kotbctakahe.hys1link.LinkConfig
import io.github.kotbctakahe.hys1link.crypto.B64
import io.github.kotbctakahe.hys1link.crypto.DeviceNameCipher
import io.github.kotbctakahe.hys1link.crypto.ProfileCipher
import io.github.kotbctakahe.hys1link.crypto.ProvisioningCipher
import io.github.kotbctakahe.hys1link.crypto.randomBytes
import io.github.kotbctakahe.hys1link.net.ApiException
import io.github.kotbctakahe.hys1link.net.ProvisioningSocket
import io.github.kotbctakahe.hys1link.proto.ProtoMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.util.KeyHelper
import java.net.URLEncoder

/**
 * Links this app to an existing Link account as a secondary device (like Link Desktop):
 *  1. open /v1/websocket/provisioning/ and receive a provisioning address
 *  2. show sgnl://linkdevice?uuid=..&pub_key=.. as a QR code, scanned in Link → Settings → Linked devices
 *  3. receive and decrypt the ProvisionMessage (identity keys, number, ACI, profile key, code)
 *  4. PUT /v1/devices/{code} with our account attributes → device id
 *  5. upload pre-keys and request contact/group sync from the primary
 */
class Linker(private val core: Core) {
    sealed class State {
        object Connecting : State()
        data class ShowQr(val url: String) : State()
        object Finishing : State()
        object Done : State()
        data class Failed(val message: String) : State()
    }

    suspend fun run(deviceName: String, onState: (State) -> Unit): Unit = withContext(Dispatchers.IO) {
        var renewals = 0
        while (true) {
            when (val r = attempt(deviceName, onState)) {
                null -> return@withContext
                else -> if (r && renewals++ < 20) continue else return@withContext
            }
        }
    }

    /** null = finished (done or failed); true = socket closed while waiting for scan, retry with a fresh QR. */
    private suspend fun attempt(deviceName: String, onState: (State) -> Unit): Boolean? {
        val cipher = ProvisioningCipher()
        val socket = ProvisioningSocket(core.http)
        var shownQr = false
        try {
            onState(State.Connecting)
            socket.open()
            while (true) {
                when (val ev = socket.events.receive()) {
                    is ProvisioningSocket.Event.Address -> {
                        val pub = B64.enc(cipher.keyPair.publicKey.serialize())
                        val url = "sgnl://linkdevice?uuid=${URLEncoder.encode(ev.uuid, "UTF-8")}&pub_key=${URLEncoder.encode(pub, "UTF-8")}"
                        shownQr = true
                        onState(State.ShowQr(url))
                    }
                    is ProvisioningSocket.Event.Envelope -> {
                        onState(State.Finishing)
                        val plain = cipher.decrypt(ev.publicKey, ev.body)
                        finish(ProtoMessage.parse(plain), deviceName)
                        onState(State.Done)
                        return null
                    }
                    is ProvisioningSocket.Event.Closed -> {
                        if (shownQr) return true
                        onState(State.Failed("Не удалось подключиться к серверу Link: ${ev.reason}"))
                        return null
                    }
                }
            }
        } catch (e: ApiException) {
            Log.w(TAG, "link failed", e)
            onState(State.Failed(describe(e)))
            return null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "link failed", e)
            onState(State.Failed(e.message ?: e.javaClass.simpleName))
            return null
        } finally {
            socket.close()
        }
    }

    private fun describe(e: ApiException) = when (e.code) {
        403 -> "Код привязки устарел. Попробуйте ещё раз."
        409 -> "Сервер отклонил устройство (несовместимые возможности): ${e.body}"
        411 -> "Достигнут лимит связанных устройств. Удалите одно в Link → Связанные устройства."
        429 -> "Слишком много попыток. Подождите немного."
        else -> "Ошибка сервера ${e.code}: ${e.body?.take(200)}"
    }

    /** ProvisionMessage fields (Link 7.4.4): 1 aciPub 2 aciPriv 3 number 4 code 5 ua 6 profileKey 7 readReceipts 8 aci 9 ver 10 pni 11 pniPub 12 pniPriv */
    private fun finish(pm: ProtoMessage, deviceName: String) {
        val aciPub = IdentityKey(pm.bytes(1) ?: error("Нет ключа личности"), 0)
        val aciPriv = Curve.decodePrivatePoint(pm.bytes(2) ?: error("Нет приватного ключа"))
        val identity = IdentityKeyPair(aciPub, aciPriv)
        val number = pm.string(3) ?: error("Нет номера")
        val code = pm.string(4) ?: error("Нет кода привязки")
        val aci = pm.string(8) ?: error("Нет ACI")
        val profileKey = pm.bytes(6)
        val pni = pm.string(10)
        val pniPair = runCatching {
            IdentityKeyPair(IdentityKey(pm.bytes(11)!!, 0), Curve.decodePrivatePoint(pm.bytes(12)!!))
        }.getOrNull()

        val password = B64.encNoPad(randomBytes(18))
        val registrationId = KeyHelper.generateRegistrationId(false)
        val pniRegistrationId = KeyHelper.generateRegistrationId(false)

        val attrs = JSONObject()
            .put("signalingKey", JSONObject.NULL)
            .put("registrationId", registrationId)
            .put("pniRegistrationId", pniRegistrationId)
            .put("voice", false)
            .put("video", false)
            .put("fetchesMessages", true)
            .put("pin", JSONObject.NULL)
            .put("registrationLock", JSONObject.NULL)
            .put("unidentifiedAccessKey", profileKey?.let { B64.enc(ProfileCipher.unidentifiedAccessKey(it)) } ?: JSONObject.NULL)
            .put("unrestrictedUnidentifiedAccess", false)
            .put("discoverableByPhoneNumber", true)
            .put("name", DeviceNameCipher.encrypt(deviceName.ifBlank { LinkConfig.DEVICE_NAME }, aciPub))
            .put("capabilities", JSONObject()
                // Same set Link 7.4.4 reports (AppCapabilities), so the server does not reject a "downgrade".
                .put("uuid", false)
                .put("gv2-3", true)
                .put("storage", false)
                .put("gv1-migration", true)
                .put("senderKey", true)
                .put("announcementGroup", true)
                .put("changeNumber", true)
                .put("stories", true)
                .put("giftBadges", true)
                .put("vds", true)
                .put("serverMessagesVersion", 1))
            .put("userDeviceInfo", JSONObject.NULL)

        val resp = core.api.finishDeviceLink(number, password, code, attrs)
        val deviceId = resp.optInt("deviceId", 0)
        require(deviceId > 1) { "Сервер не вернул номер устройства: $resp" }

        val acct = core.account
        acct.e164 = number
        acct.aci = resp.optString("uuid").takeIf { it.isNotBlank() && it != "null" } ?: aci
        acct.pni = resp.optString("pni").takeIf { it.isNotBlank() && it != "null" } ?: pni
        acct.password = password
        acct.deviceId = deviceId
        acct.registrationId = registrationId
        acct.profileKey = profileKey
        acct.identityKeyPair = identity
        acct.pniIdentityKeyPair = pniPair
        acct.sendReadReceipts = pm.bool(7) ?: true

        core.onLinked()
    }

    companion object { private const val TAG = "Linker" }
}
