package com.silo.chargemonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlin.math.max

/**
 * Charging-session statistics shared by the background service and the main screen.
 * Both may call [update]; energy is integrated over the time since the previous call,
 * whoever made it, so nothing is counted twice.
 */
object ChargeSession {
    var charging = false; private set
    var startedAt = 0L; private set
    var startLevel = 0f; private set
    var energyWh = 0.0; private set
    var peakW = 0f; private set
    private var lastTick = 0L

    @Synchronized
    fun update(s: BatterySnapshot, now: Long = SystemClock.elapsedRealtime()) {
        if (s.isCharging && !charging) { startedAt = now; startLevel = s.levelPct; energyWh = 0.0; peakW = 0f }
        val w = s.powerW ?: 0f
        if (s.isCharging && lastTick > 0 && w > 0 && now - lastTick < 5 * 60_000) {
            energyWh += w * (now - lastTick) / 3_600_000.0
            peakW = max(peakW, w)
        }
        charging = s.isCharging; lastTick = now
    }
}

/** Keeps monitoring in the background with a live status notification. */
class MonitorService : Service() {
    private lateinit var reader: BatteryReader
    private val handler = Handler(Looper.getMainLooper())
    private var lastPlugged: Boolean? = null

    private val loop = object : Runnable {
        override fun run() {
            val charging = tick()
            handler.postDelayed(this, if (charging) 3_000 else 60_000)   // fast while charging, idle otherwise
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { handler.removeCallbacks(loop); handler.post(loop) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        reader = BatteryReader(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "מצב טעינה ברקע", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val f = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerReceiver, f, RECEIVER_NOT_EXPORTED) else registerReceiver(powerReceiver, f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { exitCompletely(this); return START_NOT_STICKY }
        val n = build("מד טעינה פועל ברקע", "")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
        handler.removeCallbacks(loop); handler.post(loop)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        runCatching { unregisterReceiver(powerReceiver) }
        super.onDestroy()
    }

    /** Returns true while charging. */
    private fun tick(): Boolean {
        val s = reader.read() ?: return false
        ChargeSession.update(s)
        val plugged = s.plugType != "לא מחובר"
        if (lastPlugged != null && lastPlugged != plugged)
            RemoteLog.event(this, if (plugged) "חובר מטען: ${s.plugType}" else "המטען נותק (${s.levelPct.toInt()}%)")
        lastPlugged = plugged

        val (title, text) = when {
            s.isFull -> "✔ טעון במלואו · ${s.levelPct.toInt()}%" to String.format("%.1f°C", s.tempC)
            s.isCharging -> {
                val w = s.powerW?.let { String.format("%.1fW", max(it, 0f)) } ?: "בטעינה"
                val ttf = s.timeToFullMs?.let { " · מלא בעוד ${fmt(it)}" } ?: ""
                "⚡ $w · ${s.levelPct.toInt()}%$ttf" to
                    String.format("%s · %.1f°C · %.2f Wh נכנסו", s.plugType, s.tempC, ChargeSession.energyWh)
            }
            else -> "🔋 ${s.levelPct.toInt()}% · על סוללה" to String.format("%.1f°C", s.tempC)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(title, text))
        return s.isCharging && !s.isFull
    }

    private fun build(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "סגור אפליקציה", stop).build())
            .build()
    }

    private fun fmt(ms: Long): String {
        val m = ms / 60_000; return if (m >= 60) "${m / 60}:${String.format("%02d", m % 60)} ש׳" else "$m ד׳"
    }

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 7
        const val ACTION_STOP = "com.silo.chargemonitor.STOP"
        private const val PREFS = "monitor"

        /** Whether the user wants the app running in the background (cleared by "close app"). */
        fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

        fun start(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            runCatching { ctx.startForegroundService(Intent(ctx, MonitorService::class.java)) }
                .onFailure { RemoteLog.event(ctx, "הפעלת שירות רקע נכשלה: ${it.javaClass.simpleName} ${it.message}") }
        }

        /** Fully closes the app: stops background monitoring and every open screen. */
        fun exitCompletely(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
            RemoteLog.event(ctx, "האפליקציה נסגרה לגמרי")
            ctx.stopService(Intent(ctx, MonitorService::class.java))
            ctx.sendBroadcast(Intent(ACTION_STOP).setPackage(ctx.packageName))
        }
    }
}

/** Restarts background monitoring after a reboot or an app update, unless the user closed the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (MonitorService.isEnabled(ctx)) MonitorService.start(ctx)
    }
}
