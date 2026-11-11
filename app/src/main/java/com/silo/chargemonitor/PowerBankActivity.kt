package com.silo.chargemonitor

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class PowerBankActivity : BaseScreen() {
    private lateinit var test: PowerBankTest
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var ring: LevelRing
    private lateinit var chart: Sparkline
    private lateinit var statusText: TextView
    private lateinit var hintCard: LinearLayout
    private lateinit var hintText: TextView
    private lateinit var ratedInput: EditText
    private lateinit var setupCard: LinearLayout
    private lateinit var liveCard: LinearLayout
    private lateinit var resultCard: LinearLayout
    private lateinit var verdictText: TextView
    private lateinit var healthBar: MeterBar
    private lateinit var startBtn: TextView
    private lateinit var finishBtn: TextView

    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        test = PowerBankTest(this)
        setContentView(buildUi())
        if (test.running) PowerBankService.start(this)   // resume after app restart
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

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
        header.addView(text("🔋 בדיקת פאוור בנק", 24f, Color.WHITE, bold = true))
        root.addView(header)

        // Setup: instructions + rated capacity
        setupCard = card(0xFF2A1640.toInt(), 0xFF1A1A40.toInt()).apply {
            addView(title("איך עושים את הבדיקה"))
            addView(text("""
                1. טען את הפאוור בנק עד 100%.
                2. רשום למטה את הקיבולת שכתובה עליו (mAh).
                3. חבר את הפאוור בנק לטלפון ולחץ "התחל בדיקה".
                4. נעל את המסך ואל תשתמש בטלפון בזמן הטעינה – זה מדייק את המדידה.
                5. אם הטלפון מגיע ל־100% והפאוור בנק עוד לא נגמר: נתק, השתמש בטלפון כרגיל, וחבר שוב. הבדיקה ממשיכה לצבור.
                6. כשהפאוור בנק מפסיק לטעון (התרוקן) – לחץ "הפאוור בנק התרוקן".
            """.trimIndent(), 14f, 0xDDFFFFFF.toInt()).apply { setLineSpacing(0f, 1.3f); setPadding(0, dp(8f).toInt(), 0, 0) })
            addView(small("קיבולת לפי המדבקה (mAh)"))
            ratedInput = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(test.ratedMah.toString())
                setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(0x22FFFFFF) }
                val p = dp(10f).toInt(); setPadding(p, p, p, p)
            }
            addView(ratedInput, lp(top = 6f))
            startBtn = button("▶  התחל בדיקה", 0xFF16A34A.toInt(), 0xFF0891B2.toInt()) { startTest() }
            addView(startBtn)
        }
        root.addView(setupCard)

        // Live measurement
        liveCard = card(0xFF131A33.toInt(), 0xFF1B2450.toInt()).apply {
            statusText = text("", 15f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
            addView(statusText)
            ring = LevelRing(context); addView(ring)
            addView(grid(listOf("pbW" to "הספק כעת", "pbWh" to "אנרגיה שנכנסה לטלפון", "pbMah" to "מטען שנכנס לסוללה",
                "pbTime" to "זמן טעינה מצטבר", "pbAvg" to "הספק ממוצע", "pbPeak" to "הספק שיא",
                "pbRounds" to "סבבי חיבור", "pbRated" to "קיבולת לפי מדבקה")))
            addView(title("הספק לאורך הבדיקה").apply { setPadding(0, dp(12f).toInt(), 0, 0) })
            chart = Sparkline(context).apply { capacity = 300; color = 0xFFA78BFA.toInt(); unit = "W" }
            addView(chart, lp(top = 8f))
        }
        root.addView(liveCard)

        // Contextual hint (phone full / charging stopped)
        hintCard = card(0xFF3A2A10.toInt(), 0xFF3A1A14.toInt()).apply {
            hintText = text("", 14f, 0xFFFDE68A.toInt()).apply { setLineSpacing(0f, 1.25f) }
            addView(hintText)
        }
        root.addView(hintCard)

        finishBtn = button("⏹  הפאוור בנק התרוקן – סיים בדיקה", 0xFFDC2626.toInt(), 0xFFEA580C.toInt()) { confirmFinish() }
        root.addView(finishBtn)

        // Results
        resultCard = card(0xFF0F2E25.toInt(), 0xFF13283A.toInt()).apply {
            addView(title("תוצאות"))
            verdictText = text("", 20f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10f).toInt(), 0, dp(4f).toInt())
            }
            addView(verdictText)
            addView(small("בריאות הפאוור בנק"))
            healthBar = MeterBar(context); addView(healthBar, lp(top = 4f))
            addView(grid(listOf("rHealth" to "בריאות", "rReal" to "קיבולת אמיתית משוערת", "rRated" to "קיבולת לפי מדבקה",
                "rWh" to "אנרגיה שנכנסה לטלפון", "rOut" to "אנרגיה שיצאה מהפאוור בנק", "rCharges" to "טעינות מלאות של הטלפון")))
            addView(small("""
                * איך מחושב: פאוור בנק תקין מעביר לסוללת הטלפון כ־65% מהאנרגיה שרשומה עליו (הפסדי המרה ל־5V/9V והפסדי טעינה בטלפון).
                הבריאות = האנרגיה שנמדדה ÷ מה שפאוור בנק תקין היה אמור להעביר. זו הערכה – טווח של ±10% הוא סביר.
                שימוש בטלפון בזמן הבדיקה מקטין את התוצאה.
            """.trimIndent()))
            addView(button("↺  בדיקה חדשה", 0xFF7C3AED.toInt(), 0xFF2563EB.toInt()) { confirmReset() })
        }
        root.addView(resultCard)

        root.addView(button("ביטול ואיפוס הבדיקה", 0x33FFFFFF, 0x33FFFFFF) { confirmReset() }.also { cancelBtn = it })

        return ScrollView(this).apply { addView(root); setBackgroundColor(0xFF0B1020.toInt()) }
    }

    private var cancelBtn: TextView? = null

    // ---------------------------------------------------------------- actions

    private fun startTest() {
        val rated = ratedInput.text.toString().toIntOrNull()
        if (rated == null || rated < 500 || rated > 100_000) {
            ratedInput.error = "הזן קיבולת בין 500 ל־100,000 mAh"; return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        test.reset(keepRated = false)
        test.ratedMah = rated
        test.running = true
        PowerBankService.start(this)
        refresh()
    }

    private fun confirmFinish() {
        AlertDialog.Builder(this)
            .setTitle("לסיים את הבדיקה?")
            .setMessage("לחץ כן רק אם הפאוור בנק התרוקן ומפסיק לטעון. אם הוא עוד טוען – המשך.")
            .setPositiveButton("כן, התרוקן") { _, _ -> test.running = false; test.finished = true; refresh() }
            .setNegativeButton("המשך מדידה", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("לאפס את הבדיקה?")
            .setMessage("כל הנתונים שנמדדו יימחקו.")
            .setPositiveButton("אפס") { _, _ -> test.reset(); ratedInput.setText(test.ratedMah.toString()); refresh() }
            .setNegativeButton("ביטול", null)
            .show()
    }

    // ---------------------------------------------------------------- data

    private fun refresh() {
        val running = test.running
        val finished = test.finished
        setupCard.visibility = if (!running && !finished) View.VISIBLE else View.GONE
        liveCard.visibility = if (running) View.VISIBLE else View.GONE
        finishBtn.visibility = if (running) View.VISIBLE else View.GONE
        cancelBtn?.visibility = if (running) View.VISIBLE else View.GONE
        resultCard.visibility = if (finished) View.VISIBLE else View.GONE

        if (running) {
            val charging = test.charging
            val w = test.lastW
            statusText.text = when {
                test.phoneFull -> "📱 הטלפון מלא – ממתין"
                charging -> "⚡ מודד… ${String.format("%.1f", w)}W"
                test.rounds == 0 -> "🔌 חבר את הפאוור בנק לטלפון"
                else -> "⏸ מנותק – ממתין לחיבור"
            }
            statusText.setTextColor(if (charging && !test.phoneFull) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt())

            val pct = test.healthPct.toFloat()
            ring.level = pct.coerceAtMost(100f)
            ring.charging = charging && !test.phoneFull
            ring.title = "התקדמות מול פאוור בנק תקין"
            ring.subtitle = String.format("%.1f מתוך %.1f Wh", test.energyWh, test.expectedIntoPhoneWh)
            ring.invalidate()

            set("pbW", if (charging) String.format("%.1f W", w) else "—", 0xFFA78BFA.toInt())
            set("pbWh", String.format("%.2f Wh", test.energyWh), 0xFF4ADE80.toInt())
            set("pbMah", String.format("%.0f mAh", test.chargeMah))
            set("pbTime", fmtDuration(test.activeMs))
            set("pbAvg", String.format("%.1f W", test.avgW))
            set("pbPeak", String.format("%.1f W", test.peakW), 0xFFF472B6.toInt())
            set("pbRounds", test.rounds.toString())
            set("pbRated", "${test.ratedMah} mAh")
            chart.push(if (charging) w else 0f)

            val hint = when {
                test.phoneFull ->
                    "הטלפון הגיע ל־100% אבל הפאוור בנק עוד לא נגמר.\nנתק אותו, השתמש בטלפון כרגיל עד שהסוללה תרד (למשל ל־20%), וחבר שוב – הבדיקה תמשיך לצבור."
                !charging && test.rounds > 0 ->
                    "הטעינה נעצרה.\n• אם הפאוור בנק התרוקן (הנורות כבו / לא טוען) – לחץ \"הפאוור בנק התרוקן\".\n• אם ניתקת בכוונה – חבר שוב כשתרצה, הנתונים נשמרים."
                else -> null
            }
            hintCard.visibility = if (hint != null) View.VISIBLE else View.GONE
            hint?.let { hintText.text = it }
        } else {
            hintCard.visibility = View.GONE
            ring.charging = false
        }

        if (finished) showResults()
    }

    private fun showResults() {
        val h = test.healthPct
        val (verdict, color) = when {
            test.energyWh < 0.5 -> "לא נמדדה מספיק אנרגיה" to 0xFF94A3B8.toInt()
            h >= 90 -> "מצוין – כמו חדש" to 0xFF4ADE80.toInt()
            h >= 75 -> "טוב" to 0xFFA3E635.toInt()
            h >= 55 -> "שחוק – איבד חלק מהקיבולת" to 0xFFFACC15.toInt()
            h >= 35 -> "חלש – כדאי להחליף" to 0xFFF97316.toInt()
            else -> "גרוע / הקיבולת על המדבקה לא אמיתית" to 0xFFEF4444.toInt()
        }
        verdictText.text = String.format("%.0f%% · %s", h.coerceAtMost(120.0), verdict)
        verdictText.setTextColor(color)
        healthBar.fraction = (h / 100.0).toFloat()
        set("rHealth", String.format("%.0f%%", h), color)
        set("rReal", String.format("≈ %.0f mAh", test.realMah), color)
        set("rRated", "${test.ratedMah} mAh")
        set("rWh", String.format("%.2f Wh", test.energyWh))
        set("rOut", String.format("≈ %.1f Wh", test.bankOutputWh))
        set("rCharges", if (test.phoneCharges > 0) String.format("%.1f", test.phoneCharges) else "—", 0xFF7DD3FC.toInt())
    }

    private fun fmtDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60; val m = totalMin % 60
        return if (h > 0) "${h} ש׳ ${m} ד׳" else "${m} ד׳ ${(ms / 1000) % 60} שנ׳"
    }
}
