package ar.locky.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import ar.locky.tv.vpn.LockyVpnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Temporizador de apagado: corta la VPN a la hora elegida aunque Locky esté cerrado
 * y estés mirando otra app. Muestra una notificación con el tiempo que falta.
 */
class VpnTimerService : Service() {

    companion object {
        private const val CHANNEL_ID = "locky_timer"
        private const val NOTIF_ID = 7
        private const val PREFS = "locky"
        private const val KEY_END_AT = "timer_end_at"

        /** Programa el apagado para dentro de [minutes] minutos. */
        fun start(context: Context, minutes: Int) {
            val endAt = System.currentTimeMillis() + minutes * 60_000L
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_END_AT, endAt).apply()
            val intent = Intent(context, VpnTimerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun cancel(context: Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_END_AT).apply()
            context.stopService(Intent(context, VpnTimerService::class.java))
        }

        /** Milisegundos que faltan para el apagado, o 0 si no hay temporizador. */
        fun remainingMs(context: Context): Long {
            val endAt = context.getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_END_AT, 0)
            return (endAt - System.currentTimeMillis()).coerceAtLeast(0)
        }

        fun format(ms: Long): String {
            val totalMin = ((ms + 59_999) / 60_000).toInt()
            val h = totalMin / 60
            val m = totalMin % 60
            return when {
                h > 0 && m > 0 -> "$h h $m min"
                h > 0 -> "$h h"
                else -> "$m min"
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val tick = object : Runnable {
        override fun run() {
            val left = remainingMs(this@VpnTimerService)
            if (left <= 0) {
                shutDownVpn()
                return
            }
            notificationManager().notify(NOTIF_ID, buildNotification(left))
            handler.postDelayed(this, minOf(30_000L, left))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val left = remainingMs(this)
        startForeground(NOTIF_ID, buildNotification(left))
        if (left <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    private fun shutDownVpn() {
        scope.launch {
            val engine = LockyVpnEngine(this@VpnTimerService)
            engine.stop()
            engine.release()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_END_AT).apply()
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notificación

    private fun notificationManager() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Temporizador de Locky", NotificationManager.IMPORTANCE_LOW)
            notificationManager().createNotificationChannel(channel)
        }
    }

    private fun buildNotification(leftMs: Long): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val text = if (leftMs > 0) "La VPN se apaga en ${format(leftMs)}" else "Apagando la VPN…"
        return builder
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Locky")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }
}
