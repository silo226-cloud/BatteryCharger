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
import android.os.PowerManager
import android.os.SystemClock

/**
 * Foreground service that keeps measuring while the screen is off, because a full
 * power-bank test usually takes several hours.
 */
class PowerBankService : Service() {
    private lateinit var reader: BatteryReader
    private lateinit var test: PowerBankTest
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastTick = 0L
    private var lastCounterMah: Float? = null
    private var wasCharging = false

    private val loop = object : Runnable {
        override fun run() { sample(); handler.postDelayed(this, 2000) }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { handler.removeCallbacks(loop); handler.post(loop) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        reader = BatteryReader(this)
        test = PowerBankTest(this)
        createChannel(this)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerReceiver, f, RECEIVER_NOT_EXPORTED)
        else registerReceiver(powerReceiver, f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!test.running) { stopSelf(); return START_NOT_STICKY }
        val n = notification("מתחיל מדידה…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
        handler.removeCallbacks(loop); handler.post(loop)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        runCatching { unregisterReceiver(powerReceiver) }
        releaseWake()
        super.onDestroy()
    }

    private fun sample() {
        if (!test.running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        val s = reader.read() ?: return
        val now = SystemClock.elapsedRealtime()
        val charging = s.isCharging

        if (charging && !wasCharging) {
            test.rounds = test.rounds + 1
            lastCounterMah = s.chargeCounterMah
            lastTick = now
            acquireWake()
        }
        if (!charging) { lastCounterMah = null; releaseWake() }

        if (s.estFullCapacityMah != null && test.phoneCapacityMah == 0f) test.phoneCapacityMah = s.estFullCapacityMah

        val power = if (charging) maxOf(s.powerW ?: 0f, 0f) else 0f
        if (charging && wasCharging && lastTick > 0) {
            val dt = now - lastTick
            val counter = s.chargeCounterMah
            val prev = lastCounterMah
            // Prefer the fuel gauge's charge counter (more accurate than sampling current).
            if (counter != null && prev != null && counter >= prev) {
                val dMah = (counter - prev).toDouble()
                test.addSample(dMah * s.voltageV / 1000.0, dMah, dt, power)
            } else {
                val hours = dt / 3_600_000.0
                test.addSample(power * hours, (s.currentA ?: 0f).coerceAtLeast(0f) * 1000.0 * hours, dt, power)
            }
            if (counter != null) lastCounterMah = counter
        }
        lastTick = now
        wasCharging = charging
        test.charging = charging
        test.phoneFull = charging && (s.isFull || s.levelPct >= 100f)
        test.lastW = power

        val msg = String.format("%.2f Wh נמדדו · %s", test.energyWh,
            if (charging) String.format("%.1f W", power) else "ממתין לחיבור")
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(msg))
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChargeMonitor:PowerBankTest").apply {
                setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L)
            }
    }

    private fun releaseWake() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, PowerBankActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("בדיקת פאוור בנק פועלת")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val CHANNEL = "powerbank_test"
        const val NOTIF_ID = 42

        fun createChannel(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "בדיקת פאוור בנק", NotificationManager.IMPORTANCE_LOW))
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, PowerBankService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
