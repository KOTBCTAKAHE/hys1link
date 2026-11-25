package io.github.kotbctakahe.hys1link.net

import android.util.Log
import io.github.kotbctakahe.hys1link.LinkConfig
import io.github.kotbctakahe.hys1link.proto.ProtoMessage
import io.github.kotbctakahe.hys1link.proto.WebSocketProto
import io.github.kotbctakahe.hys1link.proto.WsRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

/**
 * Unauthenticated provisioning socket used when linking this app as a secondary device.
 * Server sends: PUT /v1/address  (ProvisioningUuid) then PUT /v1/message (ProvisionEnvelope).
 */
class ProvisioningSocket(private val http: OkHttpClient) {
    sealed class Event {
        data class Address(val uuid: String) : Event()
        data class Envelope(val publicKey: ByteArray, val body: ByteArray) : Event()
        data class Closed(val reason: String) : Event()
    }

    val events = Channel<Event>(Channel.UNLIMITED)
    private var ws: WebSocket? = null

    fun open() {
        val req = Request.Builder()
            .url("${LinkConfig.SOCKET_URL}/v1/websocket/provisioning/")
            .header("X-Signal-Agent", LinkConfig.SIGNAL_AGENT)
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val (r, _) = runCatching { WebSocketProto.decode(bytes.toByteArray()) }.getOrElse { return }
                r ?: return
                webSocket.send(WebSocketProto.response(r.id, 200, "OK").toByteString())
                val body = r.body ?: return
                when (r.path) {
                    "/v1/address" -> ProtoMessage.parse(body).string(1)?.let { events.trySend(Event.Address(it)) }
                    "/v1/message" -> {
                        val m = ProtoMessage.parse(body)
                        events.trySend(Event.Envelope(m.bytes(1) ?: ByteArray(0), m.bytes(2) ?: ByteArray(0)))
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(Event.Closed(t.message ?: "connection failed (${response?.code})"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(Event.Closed("closed $code $reason"))
            }
        })
    }

    fun close() { ws?.close(1000, "bye"); ws = null }
}

/**
 * Authenticated message socket: /v1/websocket/?login=<aci.device>&password=<pw>.
 * Incoming envelopes arrive as PUT /api/v1/message requests; we ack each after it is processed.
 */
class ChatSocket(
    private val http: OkHttpClient,
    private val creds: Creds,
    private val onRequest: suspend (WsRequest) -> Int,
    private val onState: (State) -> Unit,
) {
    enum class State { CONNECTING, CONNECTED, DISCONNECTED, AUTH_FAILED }

    private var ws: WebSocket? = null
    private val ids = AtomicLong(System.currentTimeMillis())
    private val pending = HashMap<Long, CompletableDeferred<Int>>()
    val inbox = Channel<WsRequest>(Channel.UNLIMITED)
    @Volatile var closedByUs = false
    @Volatile var lastMessageAt = 0L

    fun connect() {
        closedByUs = false
        onState(State.CONNECTING)
        val url = "${LinkConfig.SOCKET_URL}/v1/websocket/?login=" +
            URLEncoder.encode(creds.username, "UTF-8") + "&password=" + URLEncoder.encode(creds.password, "UTF-8")
        val req = Request.Builder().url(url)
            .header("X-Signal-Agent", LinkConfig.SIGNAL_AGENT)
            .header("X-Signal-Receive-Stories", "false")
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { onState(State.CONNECTED) }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastMessageAt = System.currentTimeMillis()
                val (r, resp) = runCatching { WebSocketProto.decode(bytes.toByteArray()) }.getOrElse { return }
                if (resp != null) synchronized(pending) { pending.remove(resp.id) }?.complete(resp.status)
                if (r != null) inbox.trySend(r)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failure ${response?.code}", t)
                onState(if (response?.code == 401 || response?.code == 403) State.AUTH_FAILED else State.DISCONNECTED)
                failPending()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onState(State.DISCONNECTED); failPending()
            }
        })
    }

    /** Process queued requests sequentially (call from a coroutine). */
    suspend fun pump() {
        for (r in inbox) {
            val status = try { onRequest(r) } catch (e: Exception) { Log.w(TAG, "handler failed", e); 500 }
            ws?.send(WebSocketProto.response(r.id, status, if (status == 200) "OK" else "Error").toByteString())
        }
    }

    suspend fun keepAlive(): Boolean {
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<Int>()
        synchronized(pending) { pending[id] = d }
        val sent = ws?.send(WebSocketProto.request(id, "GET", "/v1/keepalive").toByteString()) ?: false
        if (!sent) return false
        return runCatching { withTimeout(20_000) { d.await() } == 200 }.getOrDefault(false)
    }

    private fun failPending() {
        synchronized(pending) { pending.values.forEach { it.complete(-1) }; pending.clear() }
    }

    fun close() { closedByUs = true; ws?.close(1000, "bye"); ws = null; inbox.close() }

    companion object { private const val TAG = "ChatSocket" }
}
