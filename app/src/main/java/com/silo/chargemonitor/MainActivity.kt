package com.silo.chargemonitor

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

class MainActivity : BaseScreen() {
    private lateinit var reader: BatteryReader
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var chip: TextView
    private lateinit var ring: LevelRing
    private lateinit var gauge: PowerGauge
    private lateinit var speedLabel: TextView
    private lateinit var chart: Sparkline
    private lateinit var tempChart: Sparkline
    private lateinit var healthBar: MeterBar
    private lateinit var tempBar: MeterBar
    private lateinit var cableBar: MeterBar
    private lateinit var updateCard: LinearLayout
    private lateinit var updateTitle: TextView
    private lateinit var updateBtn: TextView
    private var pendingUpdate: UpdateInfo? = null
    private var installing = false
    private var autoStartUpdate = false

    // Charging-session stats (reset each time the charger is plugged in)
    private var wasCharging = false
    private var sessionStart = 0L
    private var sessionStartLevel = 0f
    private var energyWh = 0.0
    private var peakW = 0f
    private var lastTick = 0L

    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = BatteryReader(this)
        setContentView(buildUi())
        autoStartUpdate = intent.getBooleanExtra(Updater.EXTRA_START_UPDATE, false)
        runCatching { Updater.schedule(this) }   // never let the update scheduler stop the app
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Updater.EXTRA_START_UPDATE, false)) autoStartUpdate = true
    }

    override fun onResume() {
        super.onResume(); handler.post(tick)
        checkForUpdate()
    }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = dp(16f).toInt(); setPadding(p, dp(28f).toInt(), p, p)
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("⚡ מד טעינה", 26f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        chip = text("", 13f, Color.WHITE, bold = true).apply {
            val p = dp(10f).toInt(); setPadding(p, p / 2, p, p / 2)
        }
        header.addView(chip)
        root.addView(header)

        // "New version" banner, hidden until an update is found
        updateCard = card(0xFF7C2D12.toInt(), 0xFF9D174D.toInt()).apply {
            visibility = View.GONE
            updateTitle = text("", 16f, Color.WHITE, bold = true); addView(updateTitle)
            updateBtn = button("⬇  עדכן עכשיו", 0xFF16A34A.toInt(), 0xFF0891B2.toInt()) { startUpdate() }
            addView(updateBtn)
        }
        root.addView(updateCard)

        // Level ring
        ring = LevelRing(this)
        root.addView(card(0xFF131A33.toInt(), 0xFF1B2450.toInt()).apply { addView(ring) })

        root.addView(button("🔋  בדיקת בריאות פאוור בנק  ›", 0xFF7C3AED.toInt(), 0xFF2563EB.toInt()) {
            startActivity(android.content.Intent(this, PowerBankActivity::class.java))
        })

        // Power in
        root.addView(card(0xFF10243A.toInt(), 0xFF0E3B3F.toInt()).apply {
            addView(title("הספק נכנס לטלפון"))
            gauge = PowerGauge(context); addView(gauge)
            speedLabel = text("", 16f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
            addView(speedLabel)
            addView(grid(listOf("volt" to "מתח סוללה", "amp" to "זרם", "ampavg" to "זרם ממוצע", "source" to "מקור טעינה")))
        })

        // Charger & cable
        root.addView(card(0xFF2A1640.toInt(), 0xFF1A1A40.toInt()).apply {
            addView(title("מטען וכבל"))
            addView(grid(listOf("chargerW" to "הספק מקסימלי (מטען+כבל)", "chargerVA" to "מתח / זרם שסוכמו", "usage" to "ניצול המטען", "cable" to "הערכת כבל")))
            addView(small("ניצול המטען בפועל"))
            cableBar = MeterBar(context); addView(cableBar, lp(top = 4f))
            addView(small("* מערכת אנדרואיד לא חושפת את סוג הכבל ישירות. ההערכה מבוססת על ההספק שסוכם מול המטען לעומת מה שנכנס בפועל."))
        })

        // Session tiles
        root.addView(card(0xFF13283A.toInt(), 0xFF132A22.toInt()).apply {
            addView(title("סשן הטעינה הנוכחי"))
            addView(grid(listOf("ttf" to "זמן עד טעינה מלאה", "duration" to "משך טעינה", "added" to "אחוזים שנוספו",
                "energy" to "אנרגיה שנכנסה", "peak" to "הספק שיא", "rate" to "קצב (% לדקה)")))
        })

        // Charts
        root.addView(card(0xFF131A33.toInt(), 0xFF131A33.toInt()).apply {
            addView(title("גרף הספק – 2 דקות אחרונות"))
            chart = Sparkline(context).apply { color = 0xFF22C55E.toInt(); unit = "W" }
            addView(chart, lp(top = 8f))
        })

        // Temperature
        root.addView(card(0xFF3A1A14.toInt(), 0xFF2A1630.toInt()).apply {
            addView(title("טמפרטורת סוללה"))
            addView(grid(listOf("temp" to "טמפרטורה", "tempState" to "מצב")))
            tempBar = MeterBar(context).apply {
                colors = intArrayOf(0xFF06B6D4.toInt(), 0xFF22C55E.toInt(), 0xFFF59E0B.toInt(), 0xFFEF4444.toInt())
            }
            addView(small("20°C                                                     50°C"))
            addView(tempBar, lp(top = 2f))
            tempChart = Sparkline(context).apply { color = 0xFFF97316.toInt(); unit = "°C" }
            addView(tempChart, lp(top = 10f))
        })

        // Health
        root.addView(card(0xFF0F2E25.toInt(), 0xFF13283A.toInt()).apply {
            addView(title("בריאות הסוללה"))
            addView(grid(listOf("health" to "מצב (מערכת)", "healthPct" to "בריאות משוערת", "capNow" to "קיבולת משוערת",
                "capDesign" to "קיבולת מקורית", "cycles" to "מחזורי טעינה", "tech" to "סוג סוללה")))
            addView(small("בריאות משוערת"))
            healthBar = MeterBar(context); addView(healthBar, lp(top = 4f))
            addView(small("* הקיבולת משוערת לפי מונה המטען של הסוללה; מספר מחזורים זמין באנדרואיד 14 ומעלה."))
        })

        root.addView(small("גרסה ${BuildConfig.VERSION_NAME} · בודק עדכונים אוטומטית").apply {
            gravity = Gravity.CENTER; setPadding(0, dp(18f).toInt(), 0, 0)
        })

        return ScrollView(this).apply { addView(root); setBackgroundColor(0xFF0B1020.toInt()) }
    }

    // ---------------------------------------------------------------- updates

    private fun checkForUpdate() {
        if (installing) return
        Thread {
            val info = Updater.fetchNewer(this)
            runOnUiThread {
                pendingUpdate = info
                if (info == null) { updateCard.visibility = View.GONE; return@runOnUiThread }
                updateTitle.text = "🎉 גרסה חדשה ${info.versionName} זמינה" +
                    (if (info.notes.isNotBlank()) "\n${info.notes}" else "")
                updateBtn.text = "⬇  עדכן עכשיו"
                updateCard.visibility = View.VISIBLE
                if (autoStartUpdate) { autoStartUpdate = false; startUpdate() }
            }
        }.start()
    }

    private fun startUpdate() {
        val info = pendingUpdate ?: return
        if (installing) return
        if (!Updater.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("צריך אישור חד־פעמי")
                .setMessage("כדי שהאפליקציה תוכל להתקין את העדכון, אפשר לה \"התקנת אפליקציות לא מוכרות\" במסך הבא, וחזור לכאן.")
                .setPositiveButton("פתח הגדרות") { _, _ -> startActivity(Updater.unknownSourcesSettings(this)) }
                .setNegativeButton("לא עכשיו", null)
                .show()
            return
        }
        installing = true
        updateBtn.text = "מוריד… 0%"
        Thread {
            try {
                Updater.downloadAndInstall(this, info) { p -> runOnUiThread { updateBtn.text = "מוריד… $p%" } }
                runOnUiThread { updateBtn.text = "ממתין לאישור התקנה…" }
            } catch (e: Exception) {
                runOnUiThread { updateBtn.text = "⚠ ההורדה נכשלה – נסה שוב" }
            } finally {
                installing = false
            }
        }.start()
    }

    // ---------------------------------------------------------------- data

    private fun refresh() {
        val s = reader.read() ?: return
        val now = SystemClock.elapsedRealtime()

        if (s.isCharging && !wasCharging) {
            sessionStart = now; sessionStartLevel = s.levelPct; energyWh = 0.0; peakW = 0f
        }
        val power = s.powerW ?: 0f
        if (s.isCharging && lastTick > 0 && power > 0) {
            energyWh += power * (now - lastTick) / 3_600_000.0
            peakW = max(peakW, power)
        }
        wasCharging = s.isCharging; lastTick = now

        // Status chip
        val (chipText, chipColor) = when {
            s.isFull -> "✔ טעון במלואו" to 0xFF06B6D4.toInt()
            s.isCharging && s.powerW == null -> "⚡ בטעינה" to 0xFF22C55E.toInt()
            s.isCharging -> speedOf(power).let { "⚡ ${it.first}" to it.second }
            else -> "🔋 על סוללה" to 0xFF64748B.toInt()
        }
        chip.text = chipText
        chip.background = GradientDrawable().apply { cornerRadius = dp(20f); setColor(chipColor) }

        // Ring
        ring.level = s.levelPct
        ring.charging = s.isCharging && !s.isFull
        ring.title = when { s.isFull -> "טעון במלואו"; s.isCharging -> "בטעינה"; else -> "פריקה" }
        ring.subtitle = s.timeToFullMs?.let { "מלא בעוד ${fmtDuration(it)}" } ?: s.plugType
        ring.invalidate()

        // Power
        gauge.maxWatts = s.chargerMaxW ?: 0f
        gauge.watts = if (s.isCharging) max(power, 0f) else 0f
        val sp = speedOf(power)
        speedLabel.text = if (s.isCharging) sp.first else "לא מחובר למטען"
        speedLabel.setTextColor(if (s.isCharging) sp.second else 0xFF94A3B8.toInt())
        set("volt", String.format("%.2f V", s.voltageV))
        set("amp", s.currentA?.let { String.format("%.0f mA", it * 1000) } ?: "לא זמין",
            if ((s.currentA ?: 0f) >= 0) 0xFF4ADE80.toInt() else 0xFFF87171.toInt())
        set("ampavg", s.avgCurrentA?.let { String.format("%.0f mA", it * 1000) } ?: "לא זמין")
        set("source", s.plugType)

        // Charger & cable
        if (s.chargerMaxW != null) {
            set("chargerW", String.format("%.1f W", s.chargerMaxW), speedOf(s.chargerMaxW).second)
            set("chargerVA", String.format("%.1fV / %.2fA", s.chargerMaxV ?: 0f, s.chargerMaxA ?: 0f))
            val usage = if (s.chargerMaxW > 0) (power / s.chargerMaxW).coerceIn(0f, 1.2f) else 0f
            set("usage", "${(usage * 100).toInt()}%")
            cableBar.fraction = usage
            val (verdict, vc) = cableVerdict(s, power, usage)
            set("cable", verdict, vc)
        } else {
            set("chargerW", if (s.plugType == "לא מחובר") "—" else "לא מדווח")
            set("chargerVA", "—"); set("usage", "—")
            cableBar.fraction = 0f
            set("cable", if (s.isCharging) cableVerdict(s, power, null).first else "—")
        }

        // Session
        if (s.isCharging) {
            set("ttf", if (s.isFull) "מלא" else s.timeToFullMs?.let { fmtDuration(it) } ?: "מחשב…", 0xFF7DD3FC.toInt())
            val dur = now - sessionStart
            set("duration", fmtDuration(dur))
            val added = s.levelPct - sessionStartLevel
            set("added", String.format("+%.0f%%", max(added, 0f)), 0xFF4ADE80.toInt())
            set("energy", String.format("%.2f Wh", energyWh))
            set("peak", String.format("%.1f W", peakW), 0xFFF472B6.toInt())
            set("rate", if (dur > 60_000) String.format("%.2f", added / (dur / 60_000f)) else "…")
        } else {
            listOf("ttf", "duration", "added", "energy", "peak", "rate").forEach { set(it, "—") }
        }

        chart.push(if (s.isCharging) max(power, 0f) else 0f)

        // Temperature
        val (tState, tColor) = when {
            s.tempC < 15 -> "קר" to 0xFF38BDF8.toInt()
            s.tempC < 35 -> "תקין" to 0xFF4ADE80.toInt()
            s.tempC < 40 -> "חמים" to 0xFFFACC15.toInt()
            s.tempC < 45 -> "חם – מומלץ להוריד כיסוי" to 0xFFF97316.toInt()
            else -> "חם מדי! נתק מהמטען" to 0xFFEF4444.toInt()
        }
        set("temp", String.format("%.1f°C", s.tempC), tColor)
        set("tempState", tState, tColor)
        tempBar.fraction = (s.tempC - 20f) / 30f
        tempChart.push(s.tempC)

        // Health
        val (hText, hColor) = when (s.health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "טובה" to 0xFF4ADE80.toInt()
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "התחממות יתר" to 0xFFEF4444.toInt()
            BatteryManager.BATTERY_HEALTH_DEAD -> "פגומה" to 0xFFEF4444.toInt()
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "מתח יתר" to 0xFFEF4444.toInt()
            BatteryManager.BATTERY_HEALTH_COLD -> "קרה מדי" to 0xFF38BDF8.toInt()
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "תקלה" to 0xFFEF4444.toInt()
            else -> "לא ידוע" to 0xFF94A3B8.toInt()
        }
        set("health", hText, hColor)
        if (s.healthPct != null) {
            val c = when { s.healthPct >= 85 -> 0xFF4ADE80.toInt(); s.healthPct >= 75 -> 0xFFFACC15.toInt(); else -> 0xFFF87171.toInt() }
            set("healthPct", String.format("%.0f%%", s.healthPct), c)
            healthBar.fraction = s.healthPct / 100f
        } else {
            set("healthPct", "לא זמין"); healthBar.fraction = 0f
        }
        set("capNow", s.estFullCapacityMah?.let { String.format("%.0f mAh", it) } ?: "לא זמין")
        set("capDesign", s.designCapacityMah?.let { String.format("%.0f mAh", it) } ?: "לא זמין")
        set("cycles", s.cycleCount?.toString() ?: "לא זמין")
        set("tech", s.technology)
    }

    private fun speedOf(w: Float): Pair<String, Int> = when {
        w <= 0.3f -> "ממתין לזרם" to 0xFF64748B.toInt()
        w < 7.5f -> "טעינה איטית" to 0xFF3B82F6.toInt()
        w < 15f -> "טעינה רגילה" to 0xFF22C55E.toInt()
        w < 25f -> "טעינה מהירה" to 0xFFF59E0B.toInt()
        w < 45f -> "טעינה סופר־מהירה" to 0xFFF97316.toInt()
        else -> "טעינה אולטרה־מהירה" to 0xFFEC4899.toInt()
    }

    private fun cableVerdict(s: BatterySnapshot, power: Float, usage: Float?): Pair<String, Int> {
        val green = 0xFF4ADE80.toInt(); val yellow = 0xFFFACC15.toInt(); val red = 0xFFF87171.toInt()
        if (s.plugType.startsWith("טעינה אלחוטית")) return "אלחוטי – ללא כבל" to 0xFF7DD3FC.toInt()
        if (s.levelPct >= 80) return "לא ניתן לבדוק מעל 80%" to 0xFF94A3B8.toInt()  // phone throttles near full
        if (s.tempC >= 40) return "הטלפון מגביל בגלל חום" to yellow
        val maxW = s.chargerMaxW
        return when {
            maxW != null && maxW >= 15f && usage != null && usage >= 0.6f -> "כבל טוב, תומך טעינה מהירה" to green
            maxW != null && maxW >= 15f && usage != null && usage < 0.35f -> "חלש – ייתכן כבל איכותי פחות" to red
            maxW != null && maxW < 10f && s.plugType.startsWith("מטען") -> "מטען/כבל בסיסי (עד ${maxW.toInt()}W)" to yellow
            power >= 15f -> "כבל טוב" to green
            power in 0.3f..5f -> "טעינה חלשה – בדוק כבל/מטען" to yellow
            else -> "תקין" to green
        }
    }

    private fun fmtDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60; val m = totalMin % 60; val sec = (ms / 1000) % 60
        return when {
            h > 0 -> "${h} ש׳ ${m} ד׳"
            m > 0 -> "${m} ד׳ ${sec} שנ׳"
            else -> "${sec} שנ׳"
        }
    }
}
