package io.github.kotbctakahe.hys1link.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import io.github.kotbctakahe.hys1link.R
import io.github.kotbctakahe.hys1link.core.Core
import io.github.kotbctakahe.hys1link.ui.MainActivity

class Notifications(private val context: Context, private val core: Core) {
    /** Conversation currently on screen; no notifications for it. */
    @Volatile var visibleConversation: String? = null

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Новые сообщения"
        })
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Подключение", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Фоновое соединение с сервером Link"; setShowBadge(false)
        })
    }

    fun serviceNotification() = NotificationCompat.Builder(context, CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_link)
        .setContentTitle("Hys1Link на связи")
        .setContentText("Получаю сообщения в фоне")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(openIntent(null))
        .build()

    fun onIncoming(convId: String) {
        if (visibleConversation == convId) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val conv = core.repo.conversation(convId) ?: return
        val unread = core.repo.unreadIncoming(convId).takeLast(6)
        if (unread.isEmpty()) return
        val me = Person.Builder().setName("Я").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(if (conv.kind == 1) conv.title else null)
            .setGroupConversation(conv.kind == 1)
        unread.forEach { m ->
            val author = m.author?.let { core.repo.contact(it)?.displayName } ?: conv.title
            val text = when {
                m.body?.startsWith("/link=") == true -> "🧩 Стикер"
                m.body != null -> m.body
                m.attachments.firstOrNull()?.contentType?.startsWith("image/") == true -> "🖼 Фото"
                else -> "📎 Вложение"
            }
            style.addMessage(text, m.sentTs, Person.Builder().setName(author).build())
        }
        val n = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setColor(0xFF0F8B6E.toInt())
            .setStyle(style)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(convId))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(convId.hashCode(), n) }
    }

    fun cancel(convId: String) = NotificationManagerCompat.from(context).cancel(convId.hashCode())

    private fun openIntent(convId: String?): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (convId != null) putExtra(MainActivity.EXTRA_CONV, convId)
        }
        return PendingIntent.getActivity(context, convId?.hashCode() ?: 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_SERVICE = "service"
        const val SERVICE_ID = 1001
    }
}
