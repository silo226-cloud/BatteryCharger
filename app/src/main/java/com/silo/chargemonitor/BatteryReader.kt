package com.silo.chargemonitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs

/** Snapshot of everything we can learn about the battery and charger. */
data class BatterySnapshot(
    val levelPct: Float,
    val isCharging: Boolean,
    val isFull: Boolean,
    val plugType: String,
    val voltageV: Float,
    val currentA: Float?,          // positive = flowing into the battery
    val avgCurrentA: Float?,
    val powerW: Float?,            // power actually going into the phone
    val tempC: Float,
    val health: Int,
    val technology: String,
    val chargeCounterMah: Float?,
    val estFullCapacityMah: Float?,
    val designCapacityMah: Float?,
    val healthPct: Float?,
    val cycleCount: Int?,
    val chargerMaxW: Float?,       // what the charger + cable negotiated
    val chargerMaxV: Float?,
    val chargerMaxA: Float?,
    val timeToFullMs: Long?,
)

class BatteryReader(private val ctx: Context) {
    private val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val designCapacity: Float? by lazy { readDesignCapacity() }

    fun read(): BatterySnapshot? {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null

        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level >= 0) level * 100f / scale else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()

        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            (status == BatteryManager.BATTERY_STATUS_FULL && plugged != 0)
        val full = status == BatteryManager.BATTERY_STATUS_FULL

        val plugType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "מטען קיר (AC)"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB (מחשב/יציאה)"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "טעינה אלחוטית"
            8 /* BATTERY_PLUGGED_DOCK */ -> "תחנת עגינה"
            else -> "לא מחובר"
        }

        val voltageV = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).let { if (it > 1000) it / 1000f else it.toFloat() }
        val tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f

        val currentA = normalizeCurrent(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW), charging)
        val avgA = normalizeCurrent(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE), charging)
        val powerW = currentA?.let { it * voltageV }

        val counterRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val counterMah = if (counterRaw > 0 && counterRaw != Int.MIN_VALUE) {
            if (counterRaw > 100_000) counterRaw / 1000f else counterRaw.toFloat()
        } else null
        val estFull = if (counterMah != null && pct >= 5f) counterMah / (pct / 100f) else null

        val design = designCapacity
        val healthPct = if (estFull != null && design != null && design > 500f)
            (estFull / design * 100f).coerceIn(0f, 110f) else null

        val cycles = i.getIntExtra("android.os.extra.CYCLE_COUNT", -1).takeIf { it >= 0 }

        // Non-public but widely available extras describing what the charger negotiated.
        val maxUa = i.getIntExtra("max_charging_current", 0)
        val maxUv = i.getIntExtra("max_charging_voltage", 0)
        val maxA = if (maxUa > 0) (if (maxUa > 20_000) maxUa / 1_000_000f else maxUa / 1000f) else null
        val maxV = if (maxUv > 0) (if (maxUv > 100_000) maxUv / 1_000_000f else maxUv / 1000f) else null
        val chargerW = if (maxA != null && maxV != null && plugged != 0) maxA * maxV else null

        var ttf: Long? = null
        if (charging && !full) {
            if (Build.VERSION.SDK_INT >= 28) {
                bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { ttf = it }
            }
            if (ttf == null && estFull != null && currentA != null && currentA > 0.05f) {
                val remainingMah = estFull * (1f - pct / 100f)
                ttf = (remainingMah / (currentA * 1000f) * 3_600_000L).toLong()
            }
        }

        return BatterySnapshot(
            levelPct = pct, isCharging = charging, isFull = full, plugType = plugType,
            voltageV = voltageV, currentA = currentA, avgCurrentA = avgA, powerW = powerW,
            tempC = tempC, health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0),
            technology = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "—",
            chargeCounterMah = counterMah, estFullCapacityMah = estFull, designCapacityMah = design,
            healthPct = healthPct, cycleCount = cycles,
            chargerMaxW = chargerW, chargerMaxV = maxV, chargerMaxA = maxA, timeToFullMs = ttf,
        )
    }

    /** Devices report µA or mA, and with either sign; return amps, positive when charging. */
    private fun normalizeCurrent(raw: Int, charging: Boolean): Float? {
        if (raw == 0 || raw == Int.MIN_VALUE || raw == Int.MAX_VALUE) return null
        val a = if (abs(raw) >= 10_000) raw / 1_000_000f else raw / 1000f
        return if (charging) abs(a) else -abs(a)
    }

    private fun readDesignCapacity(): Float? = try {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val profile = cls.getConstructor(Context::class.java).newInstance(ctx)
        val cap = cls.getMethod("getBatteryCapacity").invoke(profile) as Double
        cap.toFloat().takeIf { it > 0 }
    } catch (_: Throwable) {
        null
    }
}
