package com.silo.chargemonitor

import android.content.Context
import kotlin.math.max

/**
 * Persistent state of a power-bank capacity test. The test accumulates the energy that
 * flows into the phone battery across one or more charging rounds until the user says
 * the power bank is empty.
 */
class PowerBankTest(ctx: Context) {
    private val p = ctx.getSharedPreferences("powerbank_test", Context.MODE_PRIVATE)

    var running: Boolean
        get() = p.getBoolean("running", false); set(v) = p.edit().putBoolean("running", v).apply()
    var finished: Boolean
        get() = p.getBoolean("finished", false); set(v) = p.edit().putBoolean("finished", v).apply()
    var ratedMah: Int
        get() = p.getInt("ratedMah", 10000); set(v) = p.edit().putInt("ratedMah", v).apply()
    var energyWh: Double
        get() = p.getFloat("energyWh", 0f).toDouble(); set(v) = p.edit().putFloat("energyWh", v.toFloat()).apply()
    var chargeMah: Double
        get() = p.getFloat("chargeMah", 0f).toDouble(); set(v) = p.edit().putFloat("chargeMah", v.toFloat()).apply()
    var activeMs: Long
        get() = p.getLong("activeMs", 0); set(v) = p.edit().putLong("activeMs", v).apply()
    var peakW: Float
        get() = p.getFloat("peakW", 0f); set(v) = p.edit().putFloat("peakW", v).apply()
    var rounds: Int
        get() = p.getInt("rounds", 0); set(v) = p.edit().putInt("rounds", v).apply()
    var lastW: Float
        get() = p.getFloat("lastW", 0f); set(v) = p.edit().putFloat("lastW", v).apply()
    var charging: Boolean
        get() = p.getBoolean("charging", false); set(v) = p.edit().putBoolean("charging", v).apply()
    var phoneFull: Boolean
        get() = p.getBoolean("phoneFull", false); set(v) = p.edit().putBoolean("phoneFull", v).apply()
    var phoneCapacityMah: Float
        get() = p.getFloat("phoneCap", 0f); set(v) = p.edit().putFloat("phoneCap", v).apply()

    fun reset(keepRated: Boolean = true) {
        val r = ratedMah
        p.edit().clear().apply()
        if (keepRated) ratedMah = r
    }

    // ---- results -------------------------------------------------------------

    /** Nominal energy printed on the bank (mAh are quoted at the 3.7 V cell voltage). */
    val ratedWh get() = ratedMah * CELL_V / 1000.0

    /** Energy a healthy bank of this rating typically gets into a phone battery. */
    val expectedIntoPhoneWh get() = ratedWh * HEALTHY_FACTOR

    /** Estimated energy that actually left the power bank (adds phone charging losses). */
    val bankOutputWh get() = energyWh / PHONE_CHARGE_EFF

    /** Estimated real capacity of the bank, expressed like the label (mAh @ 3.7 V). */
    val realMah get() = energyWh / HEALTHY_FACTOR * 1000.0 / CELL_V

    val healthPct get() = if (expectedIntoPhoneWh > 0) (energyWh / expectedIntoPhoneWh * 100.0) else 0.0

    val avgW get() = if (activeMs > 0) energyWh / (activeMs / 3_600_000.0) else 0.0

    /** How many full phone charges this bank delivered. */
    val phoneCharges get() = if (phoneCapacityMah > 0) chargeMah / phoneCapacityMah else 0.0

    fun addSample(wh: Double, mah: Double, dtMs: Long, w: Float) {
        energyWh += max(wh, 0.0)
        chargeMah += max(mah, 0.0)
        activeMs += dtMs
        peakW = max(peakW, w)
    }

    companion object {
        const val CELL_V = 3.7
        /** Typical phone charging-circuit efficiency (bank output -> battery). */
        const val PHONE_CHARGE_EFF = 0.85
        /** A healthy bank delivers ~65% of its label energy into a phone battery (boost + charging losses). */
        const val HEALTHY_FACTOR = 0.65
    }
}
