package com.silo.chargemonitor

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class WirelessActivity : BaseScreen() {
    private lateinit var reader: BatteryReader
    private lateinit var store: CableStore
    private val handler = Handler(Looper.getMainLooper())

    // live + alignment
    private lateinit var liveStatus: TextView
    private lateinit var gauge: PowerGauge
    private lateinit var alignBar: MeterBar
    private lateinit var alignText: TextView
    private var bestW = 0f
    private var lastBuzz = 0L
    // heat
    private lateinit var tempChart: Sparkline
    // pad test
    private lateinit var padName: AutoCompleteTextView
    private var withCase = true
    private lateinit var caseOn: TextView
    private lateinit var caseOff: TextView
    private lateinit var testRing: LevelRing
    private lateinit var testBtn: TextView
    private lateinit var testResult: TextView
    private lateinit var table: LinearLayout
    private var testing = false
    private var testStart = 0L
    private var testStartLevel = 0
    private val testSamples = ArrayList<Float>()
    private var testMaxW = 0f
    private var testMaxTemp = 0f
    private var testDrops = 0
    // PowerShare
    private enum class PsPhase { IDLE, BASELINE, SHARING }
    private var psPhase = PsPhase.IDLE
    private var psStart = 0L
    private val psBase = ArrayList<Float>()
    private val psShare = ArrayList<Float>()
    private lateinit var psStatus: TextView
    private lateinit var psBtn: TextView
    private lateinit var psChart: Sparkline

    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = BatteryReader(this)
        store = CableStore(this, "pads")
        setContentView(buildUi())
        renderTable()
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // measurements need the screen on
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (testing) { testing = false; testBtn.text = "▶  התחל מבחן משטח"; testResult.text = "הבדיקה הופסקה כי יצאת מהמסך"; testResult.visibility = View.VISIBLE }
        if (psPhase != PsPhase.IDLE) { psPhase = PsPhase.IDLE; psBtn.text = "▶  התחל מדידת PowerShare" }
        super.onPause()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = dp(16f).toInt(); setPadding(p, dp(28f).toInt(), p, p)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("‹", 30f, Color.WHITE, bold = true).apply {
            setPadding(0, 0, dp(12f).toInt(), 0); setOnClickListener { finish() }
        })
        header.addView(text("📡 טעינה אלחוטית", 24f, Color.WHITE, bold = true))
        root.addView(header)

        // 1. Live + alignment finder
        root.addView(card(0xFF10243A.toInt(), 0xFF1E1B4B.toInt()).apply {
            addView(title("🎯 מציאת המיקום הכי טוב על המשטח"))
            addView(small("הזז את הטלפון לאט על המשטח. כשמגיעים לשיא חדש – הטלפון ירטוט. ההספק יורד מאוד כשהסליל לא ממורכז."))
            liveStatus = text("", 15f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8f).toInt(), 0, 0) }
            addView(liveStatus)
            gauge = PowerGauge(context); addView(gauge)
            alignText = text("", 14f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
            addView(alignText)
            alignBar = MeterBar(context); addView(alignBar, lp(top = 6f))
            addView(grid(listOf("wBest" to "השיא שנמצא", "wMax" to "המטען מדווח עד", "wTemp" to "טמפרטורה", "wLevel" to "סוללה")))
            addView(button("↺  התחל חיפוש מחדש", 0x33FFFFFF, 0x33FFFFFF) { bestW = 0f })
        })

        // 2. Heat
        root.addView(card(0xFF3A1A14.toInt(), 0xFF2A1630.toInt()).apply {
            addView(title("🌡 חום בזמן טעינה אלחוטית"))
            addView(small("טעינה אלחוטית מחממת יותר. מעל ~40°C הטלפון מאט את הטעינה כדי להגן על הסוללה – הסרת כיסוי עבה עוזרת."))
            tempChart = Sparkline(context).apply { capacity = 300; color = 0xFFF97316.toInt(); unit = "°C" }
            addView(tempChart, lp(top = 8f))
        })

        // 3. Pad test
        root.addView(card(0xFF0F2E25.toInt(), 0xFF13283A.toInt()).apply {
            addView(title("📊 מבחן משטח (דקה)"))
            addView(small("תן שם למשטח, בחר עם/בלי כיסוי, מרכז את הטלפון ולחץ התחל. בדוק את אותו משטח פעם עם כיסוי ופעם בלי – ותראה כמה הכיסוי עולה."))
            padName = AutoCompleteTextView(context).apply {
                hint = "שם המשטח (למשל: מטען סמסונג בסלון)"; setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 17f
                threshold = 0
                background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(0x22FFFFFF) }
                val p = dp(10f).toInt(); setPadding(p, p, p, p)
                setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line,
                    ArrayList(store.names().map { it.substringBefore(" · ") }.distinct())))
                setOnClickListener { showDropDown() }
            }
            addView(padName, lp(top = 6f))
            val toggle = LinearLayout(context)
            caseOn = chip("📱 עם כיסוי") { withCase = true; paintCase() }
            caseOff = chip("בלי כיסוי") { withCase = false; paintCase() }
            toggle.addView(caseOn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6f).toInt() })
            toggle.addView(caseOff, LinearLayout.LayoutParams(0, -2, 1f))
            addView(toggle, lp(top = 8f)); paintCase()
            testRing = LevelRing(context).apply { title = "מבחן משטח"; subtitle = "60 שניות" }
            addView(testRing)
            testBtn = button("▶  התחל מבחן משטח", 0xFF16A34A.toInt(), 0xFF0891B2.toInt()) { if (testing) stopTest(true) else startTest() }
            addView(testBtn)
            testResult = text("", 15f, Color.WHITE, bold = true).apply { visibility = View.GONE; setPadding(0, dp(10f).toInt(), 0, 0) }
            addView(testResult)
            addView(title("השוואת משטחים").apply { setPadding(0, dp(14f).toInt(), 0, 0) })
            addView(small("ממוין מהמהיר לאיטי. לחיצה ארוכה מוחקת."))
            table = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(table, lp(top = 6f))
        })

        // 4. PowerShare
        root.addView(card(0xFF2A1640.toInt(), 0xFF172554.toInt()).apply {
            addView(title("🔄 PowerShare – כמה הטלפון נותן למכשיר אחר"))
            addView(small("""
                אנדרואיד לא מדווח כמה וואט יוצאים, לכן מעריכים לפי כמה מהר הסוללה של הטלפון מתרוקנת:
                1. נתק את הטלפון מהמטען ולחץ "התחל" – 30 שניות מדידת בסיס (בלי PowerShare).
                2. כשתתבקש: הפעל Wireless PowerShare והנח על גב הטלפון את האוזניות/השעון.
                3. המדידה ממשיכה עד שתלחץ "סיים". זו הערכה בלבד (±30%).
            """.trimIndent()))
            psStatus = text("", 15f, Color.WHITE, bold = true).apply { setPadding(0, dp(8f).toInt(), 0, 0) }
            addView(psStatus)
            psChart = Sparkline(context).apply { capacity = 180; color = 0xFFC084FC.toInt(); unit = "W" }
            addView(psChart, lp(top = 8f))
            addView(grid(listOf("psBase" to "צריכה בלי PowerShare", "psNow" to "צריכה עם PowerShare",
                "psOut" to "הטלפון נותן (משוער)", "psRecv" to "מגיע למכשיר (משוער)")))
            psBtn = button("▶  התחל מדידת PowerShare", 0xFF7C3AED.toInt(), 0xFF2563EB.toInt()) { psButton() }
            addView(psBtn)
        })

        return ScrollView(this).apply { addView(root); setBackgroundColor(0xFF0B1020.toInt()) }
    }

    private fun chip(label: String, onClick: () -> Unit) = text(label, 14f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER; val p = dp(10f).toInt(); setPadding(p, p, p, p); setOnClickListener { onClick() }
    }

    private fun paintCase() {
        fun bg(on: Boolean) = GradientDrawable().apply { cornerRadius = dp(14f); setColor(if (on) 0xFF2563EB.toInt() else 0x22FFFFFF) }
        caseOn.background = bg(withCase); caseOff.background = bg(!withCase)
    }

    // ---------------------------------------------------------------- live

    private fun refresh() {
        val s = reader.read() ?: return
        val now = SystemClock.elapsedRealtime()
        val wireless = s.plugType.startsWith("טעינה אלחוטית")
        val w = if (wireless && s.isCharging) max(s.powerW ?: 0f, 0f) else 0f
        ChargeSession.update(s, now)

        liveStatus.text = when {
            wireless && s.isFull -> "✔ הסוללה מלאה – אין מה למדוד"
            wireless -> "📡 נטען אלחוטית"
            s.plugType != "לא מחובר" -> "🔌 מחובר בכבל – הנח על משטח אלחוטי"
            else -> "הנח את הטלפון על המטען האלחוטי"
        }
        liveStatus.setTextColor(if (wireless) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt())
        gauge.maxWatts = if (wireless) s.chargerMaxW ?: 0f else 0f
        gauge.watts = w

        if (wireless && w > 0.3f) {
            if (w > bestW + 0.3f) {
                val hadBest = bestW > 0f
                bestW = w
                if (hadBest && now - lastBuzz > 800) { buzz(); lastBuzz = now }
            }
            val frac = if (bestW > 0) w / bestW else 0f
            alignBar.fraction = frac
            val (t, c) = when {
                frac >= 0.95f -> "🎯 מיקום מצוין" to 0xFF4ADE80.toInt()
                frac >= 0.8f -> "קרוב – הזז מעט" to 0xFFA3E635.toInt()
                frac >= 0.5f -> "לא ממורכז – המשך להזיז" to 0xFFFACC15.toInt()
                else -> "רחוק מהמרכז" to 0xFFF87171.toInt()
            }
            alignText.text = "$t (${(frac * 100).toInt()}% מהשיא)"; alignText.setTextColor(c)
        } else {
            alignText.text = ""; alignBar.fraction = 0f
        }
        set("wBest", if (bestW > 0) String.format("%.1f W", bestW) else "—", 0xFFF472B6.toInt())
        set("wMax", if (wireless) s.chargerMaxW?.let { String.format("%.0f W", it) } ?: "לא מדווח" else "—")
        set("wTemp", String.format("%.1f°C", s.tempC), if (s.tempC >= 40) 0xFFF87171.toInt() else if (s.tempC >= 35) 0xFFFACC15.toInt() else 0xFF4ADE80.toInt())
        set("wLevel", "${s.levelPct.toInt()}%")
        tempChart.push(s.tempC)

        if (testing) testTick(s, wireless, w, now)
        if (psPhase != PsPhase.IDLE) psTick(s, now)
    }

    private fun buzz() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
                           else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        runCatching { v?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    // ---------------------------------------------------------------- pad test

    private fun fullName(): String? {
        val n = padName.text.toString().trim()
        if (n.isEmpty()) { padName.error = "תן שם למשטח"; padName.requestFocus(); return null }
        return n + if (withCase) " · עם כיסוי" else " · בלי כיסוי"
    }

    private fun startTest() {
        fullName() ?: return
        val s = reader.read()
        if (s == null || !s.plugType.startsWith("טעינה אלחוטית")) { testResult.text = "הנח את הטלפון על המשטח האלחוטי קודם"; testResult.setTextColor(0xFFFACC15.toInt()); testResult.visibility = View.VISIBLE; return }
        if (s.isFull) { testResult.text = "הסוללה מלאה – נסה כשהיא מתחת ל־80%"; testResult.visibility = View.VISIBLE; return }
        testSamples.clear(); testMaxW = 0f; testMaxTemp = s.tempC; testDrops = 0
        testStart = SystemClock.elapsedRealtime(); testStartLevel = s.levelPct.toInt(); testing = true
        testBtn.text = "⏹  עצור"; testResult.visibility = View.GONE
        RemoteLog.event(this, "מבחן משטח התחיל: ${fullName()} סוללה $testStartLevel% מקס ${s.chargerMaxW}")
    }

    private fun testTick(s: BatterySnapshot, wireless: Boolean, w: Float, now: Long) {
        val elapsed = (now - testStart) / 1000f
        if (!wireless) testDrops++
        if (elapsed >= 10 && wireless) { testSamples += w; testMaxW = max(testMaxW, w) }   // 10 s for the pad to ramp up
        testMaxTemp = max(testMaxTemp, s.tempC)
        testRing.level = (elapsed / 60f * 100f).coerceAtMost(100f)
        testRing.charging = wireless; testRing.subtitle = "נותרו ${max(0, (60 - elapsed).toInt())} שנ׳"; testRing.invalidate()
        if (testDrops > 10) { stopTest(true, "הטלפון זז מהמשטח – נסה שוב בלי להזיז"); return }
        if (elapsed >= 60) stopTest(false)
    }

    private fun stopTest(aborted: Boolean, reason: String? = null) {
        testing = false; testBtn.text = "▶  התחל מבחן משטח"; testRing.charging = false
        if (aborted || testSamples.size < 10) {
            testResult.text = reason ?: "הבדיקה בוטלה"; testResult.setTextColor(0xFFFACC15.toInt()); testResult.visibility = View.VISIBLE; return
        }
        val name = fullName() ?: return
        val avg = testSamples.average()
        store.save(CableResult(name = name, avgW = avg, maxW = testMaxW.toDouble(), disconnects = testDrops,
            levelAtTest = testStartLevel, maxTempC = testMaxTemp.toDouble()))
        RemoteLog.event(this, String.format("מבחן משטח הסתיים: %s ממוצע %.1fW שיא %.1fW חום %.1f", name, avg, testMaxW, testMaxTemp))
        val (g, c) = CableStore.wirelessGrade(avg)
        val notes = buildList {
            if (testStartLevel >= 80) add("• הסוללה הייתה מעל 80% – הטעינה מואטת בכוונה, התוצאה נמוכה מהיכולת.")
            if (testMaxTemp >= 40) add(String.format("• הטלפון התחמם ל־%.0f°C – החום מאט את הטעינה. נסה בלי כיסוי או במקום מאוורר.", testMaxTemp))
            if (testDrops > 0) add("• הטעינה נקטעה $testDrops פעמים – הטלפון לא יציב על המשטח.")
            caseEffect(padName.text.toString().trim())?.let { add(it) }
        }
        testResult.text = "$g\n" + String.format("ממוצע %.1fW · שיא %.1fW · עד %.0f°C", avg, testMaxW, testMaxTemp) +
            (if (notes.isNotEmpty()) "\n\n" + notes.joinToString("\n") else "")
        testResult.setTextColor(c); testResult.visibility = View.VISIBLE
        renderTable()
    }

    /** When a pad was tested both with and without the case, describe what the case costs. */
    private fun caseEffect(pad: String): String? {
        val on = store.get("$pad · עם כיסוי")?.avgW ?: return null
        val off = store.get("$pad · בלי כיסוי")?.avgW ?: return null
        if (off <= 0) return null
        val diff = off - on
        return if (diff > 0.3) String.format("• 📱 הכיסוי מוריד %.1fW (%d%%) במשטח הזה.", diff, (diff / off * 100).toInt())
               else "• 📱 הכיסוי כמעט לא משפיע במשטח הזה."
    }

    private fun renderTable() {
        table.removeAllViews()
        val all = store.all()
        (padName.adapter as? ArrayAdapter<String>)?.let { a -> a.clear(); a.addAll(all.map { it.name.substringBefore(" · ") }.distinct()) }
        if (all.isEmpty()) { table.addView(small("עוד לא נבדקו משטחים.")); return }
        val df = SimpleDateFormat("d.M", Locale.getDefault())
        all.forEachIndexed { idx, c ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(0x14FFFFFF) }
                val p = dp(12f).toInt(); setPadding(p, p, p, p)
                setOnLongClickListener {
                    AlertDialog.Builder(this@WirelessActivity).setTitle("למחוק את \"${c.name}\"?")
                        .setPositiveButton("מחק") { _, _ -> store.delete(c.name); renderTable() }
                        .setNegativeButton("ביטול", null).show(); true
                }
                setOnClickListener {
                    padName.setText(c.name.substringBefore(" · ")); padName.dismissDropDown()
                    withCase = c.name.endsWith("עם כיסוי"); paintCase()
                }
            }
            val medal = when (idx) { 0 -> "🥇 "; 1 -> "🥈 "; 2 -> "🥉 "; else -> "" }
            row.addView(text(medal + c.name, 16f, Color.WHITE, bold = true))
            c.avgW?.let { val (g, gc) = CableStore.wirelessGrade(it); row.addView(text(g, 13f, gc)) }
            val parts = buildList {
                c.avgW?.let { add(String.format("%.1fW ממוצע", it)) }
                c.maxTempC?.let { add(String.format("עד %.0f°C", it)) }
                add("נבדק ${df.format(Date(c.testedAt))}")
            }
            row.addView(text(parts.joinToString(" · "), 12f, 0xB3FFFFFF.toInt()))
            table.addView(row, lp(top = 6f))
        }
    }

    // ---------------------------------------------------------------- PowerShare

    private fun psButton() {
        when (psPhase) {
            PsPhase.IDLE -> {
                val s = reader.read()
                if (s != null && s.plugType != "לא מחובר") { psStatus.text = "נתק את הטלפון מהמטען קודם"; psStatus.setTextColor(0xFFFACC15.toInt()); return }
                if (s?.currentA == null) { psStatus.text = "הטלפון לא מדווח זרם – אי אפשר למדוד"; return }
                psBase.clear(); psShare.clear(); psStart = SystemClock.elapsedRealtime(); psPhase = PsPhase.BASELINE
                psBtn.text = "⏹  בטל"
                RemoteLog.event(this, "מדידת PowerShare התחילה")
            }
            PsPhase.BASELINE -> { psPhase = PsPhase.IDLE; psBtn.text = "▶  התחל מדידת PowerShare"; psStatus.text = "בוטל" }
            PsPhase.SHARING -> {
                psPhase = PsPhase.IDLE; psBtn.text = "▶  התחל מדידת PowerShare"
                val out = estimate()
                psStatus.text = if (out != null) String.format("✓ הסתיים: הטלפון נתן בערך %.1fW (כ־%.1fW הגיעו למכשיר)", out, out * PS_EFFICIENCY)
                                else "✓ הסתיים – לא זוהתה צריכה נוספת. ייתכן ש־PowerShare לא הופעל או שהמכשיר כבר מלא."
                RemoteLog.event(this, "מדידת PowerShare הסתיימה: ${psStatus.text}")
            }
        }
    }

    /** Discharge power right now, in W (positive = leaving the battery). */
    private fun drain(s: BatterySnapshot) = max(-(s.powerW ?: 0f), 0f)

    private fun psTick(s: BatterySnapshot, now: Long) {
        if (s.plugType != "לא מחובר") { psPhase = PsPhase.IDLE; psBtn.text = "▶  התחל מדידת PowerShare"; psStatus.text = "הטלפון חובר למטען – המדידה הופסקה"; return }
        val d = drain(s)
        psChart.push(d)
        val elapsed = (now - psStart) / 1000
        when (psPhase) {
            PsPhase.BASELINE -> {
                psBase += d
                psStatus.text = "⏳ מודד צריכה רגילה… ${max(0, 30 - elapsed)} שנ׳ (אל תפעיל עדיין PowerShare)"
                psStatus.setTextColor(0xFF7DD3FC.toInt())
                set("psBase", String.format("%.2f W", psBase.average()))
                if (elapsed >= 30) {
                    psPhase = PsPhase.SHARING; psStart = now
                    psBtn.text = "⏹  סיים מדידה"
                    buzz()
                    psStatus.text = "✅ עכשיו: הפעל Wireless PowerShare והנח את המכשיר על גב הטלפון"
                    psStatus.setTextColor(0xFF4ADE80.toInt())
                }
            }
            PsPhase.SHARING -> {
                if (elapsed >= 5) psShare += d   // let PowerShare start
                if (psShare.size > 120) psShare.removeAt(0)  // rolling 2-minute window
                set("psNow", if (psShare.isEmpty()) "…" else String.format("%.2f W", psShare.average()))
                val out = estimate()
                set("psOut", out?.let { String.format("≈ %.1f W", it) } ?: "…", 0xFFC084FC.toInt())
                set("psRecv", out?.let { String.format("≈ %.1f W", it * PS_EFFICIENCY) } ?: "…", 0xFF4ADE80.toInt())
                if (elapsed >= 15 && out == null) {
                    psStatus.text = "לא רואים צריכה נוספת – ודא ש־PowerShare פועל והמכשיר מונח במרכז"
                    psStatus.setTextColor(0xFFFACC15.toInt())
                } else if (out != null) {
                    psStatus.text = "🔄 מודד… הזז את המכשיר אם ההספק נמוך"
                    psStatus.setTextColor(0xFFC084FC.toInt())
                }
            }
            PsPhase.IDLE -> {}
        }
    }

    /** Extra drain caused by PowerShare, or null when there is no meaningful difference yet. */
    private fun estimate(): Float? {
        if (psBase.size < 10 || psShare.size < 5) return null
        val extra = (psShare.average() - psBase.average()).toFloat()
        return extra.takeIf { it > 0.4f }
    }

    companion object {
        /** Typical end-to-end efficiency of reverse wireless charging (phone battery -> accessory). */
        private const val PS_EFFICIENCY = 0.6f
    }
}
