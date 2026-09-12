package dev.kimiterminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.kimiterminal.MainActivity
import dev.kimiterminal.R
import dev.kimiterminal.acp.SessionState

/**
 * Foreground-сервис сессии агента (T6).
 *
 * Уведомление — единственное, что пользователь видит, когда приложение свёрнуто.
 * Поэтому его текст строится из [SessionState] + причины, а не из «последней строки терминала».
 *
 * ВАЖНО про границы (BLUEPRINT §9.1 S4): сервис поднимает процесс, но НЕ открывает
 * порт наружу и НЕ хранит bearer-токен `kimi web` в открытом виде.
 */
class AgentForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = SessionState.valueOf(intent?.getStringExtra(EXTRA_STATE) ?: SessionState.IDLE.name)
        val session = intent?.getStringExtra(EXTRA_SESSION)?.take(18) ?: "—"
        val reason = intent?.getStringExtra(EXTRA_REASON)
        val n = build(this, state, session, reason)
        startForegroundCompat(n)
        return START_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                return
            } catch (_: SecurityException) { /* тип не заявлен в манифесте сборщика — fallback ниже */ }
        }
        startForeground(NOTIF_ID, n)
    }

    override fun onDestroy() {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 1001
        const val CH_FOREGROUND = "agent_foreground"
        const val CH_ATTENTION = "agent_attention"
        const val CH_DONE = "agent_done"
        const val EXTRA_STATE = "state"
        const val EXTRA_SESSION = "session"
        const val EXTRA_REASON = "reason"

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(
                CH_FOREGROUND, "Сессия агента", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянная: агент работает"; setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(
                CH_ATTENTION, "Нужно внимание", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "NEEDS_INPUT / FAILED"; enableVibration(true)
            })
            nm.createNotificationChannel(NotificationChannel(
                CH_DONE, "Готово", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "DONE / CANCELLED"
            })
        }

        fun channelFor(s: SessionState): String = when (s) {
            SessionState.WAITING_APPROVAL, SessionState.WAITING_USER,
            SessionState.AUTH_REQUIRED, SessionState.FAILED, SessionState.DEAD -> CH_ATTENTION
            SessionState.DONE, SessionState.CANCELLED -> CH_DONE
            else -> CH_FOREGROUND
        }

        fun label(s: SessionState): String = when (s) {
            SessionState.IDLE -> "Ожидание"
            SessionState.AUTH_REQUIRED -> "Нужен вход"
            SessionState.READY -> "Готов"
            SessionState.RUNNING -> "Работает"
            SessionState.WAITING_APPROVAL -> "Ждёт разрешения"
            SessionState.WAITING_USER -> "Ждёт ответа"
            SessionState.DONE -> "Готово"
            SessionState.FAILED -> "Ошибка"
            SessionState.CANCELLED -> "Остановлено"
            SessionState.DEAD -> "Процесс умер"
        }

        fun build(ctx: Context, s: SessionState, session: String, reason: String?): Notification {
            val pi = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setData(android.net.Uri.parse("kimiterminal://session/$session")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = listOfNotNull(label(s), session, reason?.takeIf { it.isNotBlank() }).joinToString(" · ")
            return NotificationCompat.Builder(ctx, channelFor(s))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Agent Phone")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(s == SessionState.RUNNING || s == SessionState.WAITING_APPROVAL || s == SessionState.WAITING_USER)
                .setPriority(if (channelFor(s) == CH_ATTENTION) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build()
        }

        fun start(ctx: Context, s: SessionState, session: String, reason: String?) {
            val i = Intent(ctx, AgentForegroundService::class.java)
                .putExtra(EXTRA_STATE, s.name).putExtra(EXTRA_SESSION, session).putExtra(EXTRA_REASON, reason)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
