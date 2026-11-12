package com.silo.chargemonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Result of testing one cable. Fields stay null until that part of the test was run. */
data class CableResult(
    val name: String,
    val avgW: Double? = null,
    val maxW: Double? = null,
    val maxA: Double? = null,
    val disconnects: Int? = null,
    val dips: Int? = null,
    val chargerMaxW: Double? = null,
    val levelAtTest: Int? = null,
    val data: Boolean? = null,          // true = carries data, false = charge only
    val dataDetail: String? = null,
    val maxTempC: Double? = null,
    val testedAt: Long = System.currentTimeMillis(),
)

/** Saved cable results, one entry per cable name (latest measurement wins, per part). */
class CableStore(ctx: Context, prefsName: String = "cables") {
    private val p = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun all(): List<CableResult> {
        val arr = JSONArray(p.getString("list", "[]"))
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            .sortedByDescending { it.avgW ?: -1.0 }
    }

    fun get(name: String) = all().firstOrNull { it.name == name }

    fun names() = all().map { it.name }

    /** Insert or merge: fields that are null in [r] keep the previously saved value. */
    fun save(r: CableResult) {
        val old = get(r.name)
        val merged = if (old == null) r else CableResult(
            name = r.name,
            avgW = r.avgW ?: old.avgW, maxW = r.maxW ?: old.maxW, maxA = r.maxA ?: old.maxA,
            disconnects = r.disconnects ?: old.disconnects, dips = r.dips ?: old.dips,
            chargerMaxW = r.chargerMaxW ?: old.chargerMaxW, levelAtTest = r.levelAtTest ?: old.levelAtTest,
            data = r.data ?: old.data, dataDetail = r.dataDetail ?: old.dataDetail,
            maxTempC = r.maxTempC ?: old.maxTempC,
            testedAt = r.testedAt,
        )
        val list = all().filter { it.name != r.name } + merged
        p.edit().putString("list", JSONArray(list.map(::toJson)).toString()).apply()
    }

    fun delete(name: String) {
        val list = all().filter { it.name != name }
        p.edit().putString("list", JSONArray(list.map(::toJson)).toString()).apply()
    }

    private fun toJson(r: CableResult) = JSONObject().apply {
        put("name", r.name); put("testedAt", r.testedAt)
        r.avgW?.let { put("avgW", it) }; r.maxW?.let { put("maxW", it) }; r.maxA?.let { put("maxA", it) }
        r.disconnects?.let { put("disconnects", it) }; r.dips?.let { put("dips", it) }
        r.chargerMaxW?.let { put("chargerMaxW", it) }; r.levelAtTest?.let { put("level", it) }
        r.data?.let { put("data", it) }; r.dataDetail?.let { put("dataDetail", it) }
        r.maxTempC?.let { put("maxTempC", it) }
    }

    private fun fromJson(o: JSONObject) = CableResult(
        name = o.getString("name"),
        avgW = o.optDouble("avgW").takeUnless { it.isNaN() },
        maxW = o.optDouble("maxW").takeUnless { it.isNaN() },
        maxA = o.optDouble("maxA").takeUnless { it.isNaN() },
        disconnects = if (o.has("disconnects")) o.getInt("disconnects") else null,
        dips = if (o.has("dips")) o.getInt("dips") else null,
        chargerMaxW = o.optDouble("chargerMaxW").takeUnless { it.isNaN() },
        levelAtTest = if (o.has("level")) o.getInt("level") else null,
        data = if (o.has("data")) o.getBoolean("data") else null,
        dataDetail = o.optString("dataDetail").ifBlank { null },
        maxTempC = o.optDouble("maxTempC").takeUnless { it.isNaN() },
        testedAt = o.optLong("testedAt", 0),
    )

    companion object {
        /**
         * Grade for wireless pads, from power reaching the battery. Wireless loses part of the
         * pad's rated power to heat, so a 15 W pad typically delivers about 10-12 W to the battery.
         */
        fun wirelessGrade(avgW: Double): Pair<String, Int> = when {
            avgW >= 10 -> "🚀 מהירה (מטען 15W / Qi2)" to 0xFF4ADE80.toInt()
            avgW >= 6.5 -> "✅ טובה (מטען 10W)" to 0xFFA3E635.toInt()
            avgW >= 4.5 -> "🟡 רגילה (מטען 7.5W)" to 0xFFFACC15.toInt()
            else -> "🔴 איטית (5W או לא ממורכז)" to 0xFFF87171.toInt()
        }

        /** Grade from the steady average power (W) measured into the phone. */
        fun grade(avgW: Double): Pair<String, Int> = when {
            avgW >= 25 -> "🚀 מצוין – טעינה מהירה (3A ומעלה)" to 0xFF4ADE80.toInt()
            avgW >= 15 -> "✅ טוב – טעינה מהירה בסיסית" to 0xFFA3E635.toInt()
            avgW >= 7.5 -> "🟡 בינוני – טעינה רגילה" to 0xFFFACC15.toInt()
            else -> "🔴 חלש – כבל דק/פגום או מטען איטי" to 0xFFF87171.toInt()
        }
    }
}
