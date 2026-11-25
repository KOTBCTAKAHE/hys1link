package io.github.kotbctakahe.hys1link.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.kotbctakahe.hys1link.core.Core

/**
 * Link delivers pushes through its own Firebase project, which a third-party client cannot use.
 * Instead we keep the message websocket open in a foreground service.
 */
class ConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val core = Core.get(this)
        if (!core.account.isLinked) { stopSelf(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        ServiceCompat.startForeground(this, Notifications.SERVICE_ID, core.notifications.serviceNotification(), type)
        core.startConnection()
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java)) }
                .onFailure { Core.get(context).startConnection() }
        }
        fun stop(context: Context) { context.stopService(Intent(context, ConnectionService::class.java)) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Core.get(context).account.isLinked) ConnectionService.start(context)
    }
}
