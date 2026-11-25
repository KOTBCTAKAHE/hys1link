package io.github.kotbctakahe.hys1link.core

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import io.github.kotbctakahe.hys1link.net.ChatSocket
import io.github.kotbctakahe.hys1link.net.Http
import io.github.kotbctakahe.hys1link.net.LinkApi
import io.github.kotbctakahe.hys1link.service.Notifications
import io.github.kotbctakahe.hys1link.store.Db
import io.github.kotbctakahe.hys1link.store.ProtocolStore
import io.github.kotbctakahe.hys1link.store.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

fun CoroutineScope.launchIo(block: suspend CoroutineScope.() -> Unit): Job = launch(Dispatchers.IO, block = block)

/** Process-wide wiring of the client. */
@SuppressLint("StaticFieldLeak")
class Core private constructor(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = Db(context)
    val repo = Repo(db)
    val account = Account(db)
    val http: OkHttpClient = Http.client(context)
    val api = LinkApi(http) { account.creds() }
    val keys = Keys(this)
    val sender = Sender(this)
    val receiver = Receiver(this)
    val profiles = Profiles(this)
    val attachments = Attachments(this)
    val groups = Groups(this)
    val notifications = Notifications(context, this)
    val social = io.github.kotbctakahe.hys1link.social.SocialClient(this)

    @Volatile var store: ProtocolStore? = null
        private set

    enum class Conn { OFFLINE, CONNECTING, ONLINE, AUTH_FAILED }
    private val _conn = MutableStateFlow(Conn.OFFLINE)
    val connection: StateFlow<Conn> = _conn
    private val _linked = MutableStateFlow(account.isLinked)
    val linked: StateFlow<Boolean> = _linked

    private var loop: Job? = null

    init { if (account.isLinked) openStore() }

    private fun openStore() {
        store = ProtocolStore(db, account.identityKeyPair!!, account.registrationId)
    }

    /** Called by Linker once the server accepted this device. Blocking; runs on IO. */
    fun onLinked() {
        openStore()
        runCatching { keys.refreshPreKeys(force = true) }.onFailure { Log.w(TAG, "initial prekeys failed, will retry on connect", it) }
        runCatching { sender.requestInitialSync() }.onFailure { Log.w(TAG, "initial sync request failed", it) }
        repo.ensureDirect(account.aci!!, account.aci)
        _linked.value = true
        scope.launchIo { runCatching { profiles.refreshSelf() } }
    }

    fun onQueueEmpty() {
        if (System.currentTimeMillis() - account.lastContactSync > 24 * 3600_000L) scope.launchIo { sender.requestInitialSync() }
    }

    /** Keeps the authenticated websocket alive with backoff; idempotent. */
    @Synchronized
    fun startConnection() {
        if (!account.isLinked || loop?.isActive == true) return
        loop = scope.launchIo {
            var backoff = 2_000L
            launchIo { runCatching { keys.refreshPreKeys() }.onFailure { Log.w(TAG, "prekeys", it) } }
            while (isActive && account.isLinked) {
                val socket = ChatSocket(http, account.creds()!!, { req -> receiver.handle(req) }) { s ->
                    _conn.value = when (s) {
                        ChatSocket.State.CONNECTING -> Conn.CONNECTING
                        ChatSocket.State.CONNECTED -> Conn.ONLINE
                        ChatSocket.State.AUTH_FAILED -> Conn.AUTH_FAILED
                        ChatSocket.State.DISCONNECTED -> Conn.OFFLINE
                    }
                }
                socket.connect()
                val pump = launchIo { socket.pump() }
                // keep-alive / health check
                var sinceKeepAlive = 0
                while (isActive) {
                    delay(1_000)
                    if (_conn.value == Conn.AUTH_FAILED || _conn.value == Conn.OFFLINE) break
                    if (++sinceKeepAlive < 30) continue
                    sinceKeepAlive = 0
                    if (!socket.keepAlive()) { Log.w(TAG, "keepalive failed"); break }
                    backoff = 2_000L
                }
                socket.close()
                pump.cancel()
                if (_conn.value == Conn.AUTH_FAILED) { Log.w(TAG, "auth failed — device probably unlinked"); break }
                _conn.value = Conn.OFFLINE
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    fun stopConnection() { loop?.cancel(); loop = null; _conn.value = Conn.OFFLINE }

    fun unlink() {
        stopConnection()
        runCatching { if (account.deviceId > 1) api.removeDevice(account.deviceId) }
        db.wipe()
        context.filesDir.resolve("attachments").deleteRecursively()
        context.filesDir.resolve("avatars").deleteRecursively()
        store = null
        _linked.value = false
    }

    companion object {
        private const val TAG = "Core"
        @Volatile private var instance: Core? = null
        fun get(context: Context): Core = instance ?: synchronized(this) {
            instance ?: Core(context.applicationContext).also { instance = it }
        }
    }
}
