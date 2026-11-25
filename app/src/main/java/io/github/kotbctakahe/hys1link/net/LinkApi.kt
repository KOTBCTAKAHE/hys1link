package io.github.kotbctakahe.hys1link.net

import io.github.kotbctakahe.hys1link.LinkConfig
import io.github.kotbctakahe.hys1link.crypto.B64
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

class ApiException(val code: Int, val body: String?) : Exception("HTTP $code: ${body?.take(300)}")

data class Creds(val username: String, val password: String)

data class OutgoingMessage(val type: Int, val destinationDeviceId: Int, val destinationRegistrationId: Int, val content: ByteArray)

/** REST client for chat.linkmessenger.me (Signal-Server ~2022 API plus Link extensions). */
class LinkApi(private val http: OkHttpClient, private val creds: () -> Creds?) {
    private val json = "application/json".toMediaType()

    private fun call(method: String, path: String, body: JSONObject? = null, auth: Creds? = creds(),
                     base: String = LinkConfig.SERVICE_URL, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(base + path)
        if (auth != null) b.header("Authorization", Credentials.basic(auth.username, auth.password))
        headers.forEach { (k, v) -> b.header(k, v) }
        val rb = body?.toString()?.toRequestBody(json)
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete(rb)
            else -> b.method(method, rb ?: ByteArray(0).toRequestBody(json))
        }
        http.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) throw ApiException(resp.code, text)
            return text ?: ""
        }
    }

    // ---- linking ----
    /** PUT /v1/devices/{code} — finish secondary device registration (auth: number:password). */
    fun finishDeviceLink(number: String, password: String, provisioningCode: String, attrs: JSONObject): JSONObject {
        val text = call("PUT", "/v1/devices/$provisioningCode", attrs, Creds(number, password))
        return JSONObject(text.ifBlank { "{}" })
    }

    fun devices(): JSONArray = JSONObject(call("GET", "/v1/devices/")).optJSONArray("devices") ?: JSONArray()

    fun removeDevice(id: Int) { call("DELETE", "/v1/devices/$id") }

    // ---- keys ----
    fun registerPreKeys(identityKey: IdentityKey, signed: SignedPreKeyRecord, preKeys: List<PreKeyRecord>, identity: String = "aci") {
        val body = JSONObject()
            .put("identityKey", B64.encNoPad(identityKey.serialize()))
            .put("signedPreKey", JSONObject()
                .put("keyId", signed.id)
                .put("publicKey", B64.encNoPad(signed.keyPair.publicKey.serialize()))
                .put("signature", B64.encNoPad(signed.signature)))
            .put("preKeys", JSONArray().apply {
                preKeys.forEach { put(JSONObject().put("keyId", it.id).put("publicKey", B64.encNoPad(it.keyPair.publicKey.serialize()))) }
            })
        call("PUT", "/v2/keys?identity=$identity", body)
    }

    fun preKeyCount(identity: String = "aci"): Int = JSONObject(call("GET", "/v2/keys?identity=$identity")).optInt("count", 0)

    /** GET /v2/keys/{uuid}/{device|*} */
    fun preKeyBundles(uuid: String, device: String = "*"): List<PreKeyBundle> {
        val o = JSONObject(call("GET", "/v2/keys/$uuid/$device"))
        val identity = IdentityKey(B64.dec(o.getString("identityKey")), 0)
        val devices = o.getJSONArray("devices")
        return (0 until devices.length()).map { i ->
            val d = devices.getJSONObject(i)
            val pre = d.optJSONObject("preKey")
            val signed = d.optJSONObject("signedPreKey")
            PreKeyBundle(
                d.getInt("registrationId"),
                d.getInt("deviceId"),
                pre?.getInt("keyId") ?: -1,
                pre?.let { ECPublicKey(B64.dec(it.getString("publicKey"))) },
                signed?.getInt("keyId") ?: -1,
                signed?.let { ECPublicKey(B64.dec(it.getString("publicKey"))) },
                signed?.let { B64.dec(it.getString("signature")) },
                identity,
            )
        }
    }

    // ---- messages ----
    sealed class SendResult {
        object Ok : SendResult()
        data class Mismatched(val missing: List<Int>, val extra: List<Int>) : SendResult()
        data class Stale(val stale: List<Int>) : SendResult()
    }

    fun sendMessages(destination: String, timestamp: Long, messages: List<OutgoingMessage>, online: Boolean = false, urgent: Boolean = true): SendResult {
        val body = JSONObject()
            .put("destination", destination)
            .put("timestamp", timestamp)
            .put("online", online)
            .put("urgent", urgent)
            .put("messages", JSONArray().apply {
                messages.forEach {
                    put(JSONObject()
                        .put("type", it.type)
                        .put("destinationDeviceId", it.destinationDeviceId)
                        .put("destinationRegistrationId", it.destinationRegistrationId)
                        .put("content", B64.enc(it.content)))
                }
            })
        return try {
            call("PUT", "/v1/messages/$destination", body)
            SendResult.Ok
        } catch (e: ApiException) {
            when (e.code) {
                409 -> JSONObject(e.body ?: "{}").let {
                    SendResult.Mismatched(it.optJSONArray("missingDevices").ints(), it.optJSONArray("extraDevices").ints())
                }
                410 -> SendResult.Stale(JSONObject(e.body ?: "{}").optJSONArray("staleDevices").ints())
                else -> throw e
            }
        }
    }

    // ---- profiles ----
    fun versionedProfile(uuid: String, version: String): JSONObject = JSONObject(call("GET", "/v1/profile/$uuid/$version"))
    fun profile(uuid: String): JSONObject = JSONObject(call("GET", "/v1/profile/$uuid"))

    /** Unauthenticated profile fetch using the unidentified-access key (works before linking). */
    fun profileUnidentified(uuid: String, accessKey: ByteArray): JSONObject =
        JSONObject(call("GET", "/v1/profile/$uuid", auth = null, headers = mapOf("Unidentified-Access-Key" to B64.enc(accessKey))))

    fun whoAmI(): JSONObject = JSONObject(call("GET", "/v1/accounts/whoami"))

    // ---- uploads (Link: v4 form + TUS on upload.linkm.me) ----
    data class UploadForm(val cdn: Int, val key: String, val headers: Map<String, String>, val location: String)

    fun uploadForm(length: Long): UploadForm {
        val o = JSONObject(call("GET", "/v4/attachments/form/upload?uploadLength=$length"))
        val h = o.optJSONObject("headers")
        val headers = h?.keys()?.asSequence()?.associateWith { h.getString(it) } ?: emptyMap()
        return UploadForm(o.getInt("cdn"), o.getString("key"), headers, o.getString("signedUploadLocation"))
    }

    /** TUS create (POST) + upload (PATCH), as PushServiceSocket.createTusUpload / uploadToCdn3. */
    fun tusUpload(form: UploadForm, blob: ByteArray) {
        val sha = B64.enc(java.security.MessageDigest.getInstance("SHA-256").digest(blob))
        val meta = "filename " + B64.enc(form.key.toByteArray())
        val create = Request.Builder().url(form.location)
            .post(ByteArray(0).toRequestBody(null))
            .header("Tus-Resumable", "1.0.0")
            .header("Upload-Length", blob.size.toString())
            .header("Upload-Metadata", meta)
            .header("X-Signal-Checksum-Sha256", sha)
            .apply { form.headers.forEach { (k, v) -> if (k.lowercase() !in tusReserved) header(k, v) } }
            .build()
        val location = http.newCall(create).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "TUS create: " + r.body?.string())
            r.header("Location") ?: throw ApiException(r.code, "TUS create: no Location")
        }
        val target = resolve(form.location, location)
        val patch = Request.Builder().url(target)
            .patch(blob.toRequestBody("application/offset+octet-stream".toMediaType()))
            .header("Tus-Resumable", "1.0.0")
            .header("Upload-Offset", "0")
            .apply { form.headers.forEach { (k, v) -> if (k.lowercase() !in tusReserved) header(k, v) } }
            .build()
        http.newCall(patch).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "TUS upload: " + r.body?.string())
        }
    }

    private val tusReserved = setOf("content-length", "host", "tus-resumable", "upload-metadata", "upload-length", "upload-offset", "content-type")

    private fun resolve(base: String, loc: String): String =
        if (loc.startsWith("http://") || loc.startsWith("https://")) loc
        else (base.toHttpUrlOrNull()?.resolve(loc)?.toString() ?: loc)

    // ---- cdn ----
    fun downloadCdn(cdnNumber: Int, idOrKey: String, maxBytes: Long = 100L * 1024 * 1024): ByteArray {
        val base = LinkConfig.CDN[cdnNumber] ?: LinkConfig.CDN.getValue(0)
        val candidates = listOf("$base/$idOrKey", "$base/attachments/$idOrKey")
        var last: Exception? = null
        for (url in candidates) {
            try {
                http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw ApiException(resp.code, null)
                    val len = resp.body?.contentLength() ?: -1
                    if (len > maxBytes) throw ApiException(413, "Attachment too large")
                    return resp.body!!.bytes()
                }
            } catch (e: Exception) { last = e }
        }
        throw last ?: ApiException(404, null)
    }

    fun downloadProfileAvatar(path: String): ByteArray =
        http.newCall(Request.Builder().url("${LinkConfig.CDN.getValue(0)}/$path").get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, null)
            resp.body!!.bytes()
        }

    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { getInt(it) }
}
