package com.silo.chargemonitor

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class CableTestActivity : BaseScreen() {
    private lateinit var reader: BatteryReader
    private lateinit var store: CableStore
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var nameInput: AutoCompleteTextView
    // load test
    private lateinit var ring: LevelRing
    private lateinit var chart: Sparkline
    private lateinit var loadStatus: TextView
    private lateinit var loadBtn: TextView
    private lateinit var resultText: TextView
    // data test
    private lateinit var dataStatus: TextView
    private lateinit var dataDetail: TextView
    private lateinit var dataSaveBtn: TextView
    // table
    private lateinit var table: LinearLayout

    // ---- load test state
    private var running = false
    private var startedAt = 0L
    private val samples = ArrayList<Float>()
    private var maxW = 0f
    private var maxA = 0f
    private var disconnects = 0
    private var dips = 0
    private var wasPlugged = true
    private var unpluggedSince = 0L
    private var chargerMaxW: Float? = null
    private var startLevel = 0

    // ---- data test state
    private var usbState: Intent? = null
    private var pluggedSince = 0L
    private var dataVerdict: Boolean? = null
    private var dataVerdictText = ""

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { usbState = i }
    }

    private val tick = object : Runnable {
        override fun run() { sample(); refreshData(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = BatteryReader(this)
        store = CableStore(this)
        setContentView(buildUi())
        renderTable()
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(ACTION_USB_STATE)
        usbState = if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, RECEIVER_EXPORTED)
                   else registerReceiver(usbReceiver, f)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(usbReceiver) }
        if (running) stopLoad(aborted = true, reason = "הבדיקה הופסקה כי יצאת מהמסך")
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
        header.addView(text("🔌 בדיקת כבל", 24f, Color.WHITE, bold = true))
        root.addView(header)

        // Cable name
        root.addView(card(0xFF1E1B4B.toInt(), 0xFF172554.toInt()).apply {
            addView(title("איזה כבל בודקים?"))
            addView(small("תן לכבל שם כדי להשוות בין כבלים (למשל: \"לבן מהמגירה\", \"של סמסונג\")"))
            nameInput = AutoCompleteTextView(context).apply {
                hint = "שם הכבל"; setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); textSize = 18f
                threshold = 0
                background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(0x22FFFFFF) }
                val p = dp(10f).toInt(); setPadding(p, p, p, p)
                setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, ArrayList(store.names())))
                setOnClickListener { showDropDown() }
            }
            addView(nameInput, lp(top = 6f))
        })

        // 1. Charging / load test
        root.addView(card(0xFF10243A.toInt(), 0xFF0E3B3F.toInt()).apply {
            addView(title("1. מבחן טעינה (דקה)"))
            addView(small("""
                • השתמש באותו מטען מהיר לכל הכבלים – כך ההשוואה הוגנת.
                • הכי מדויק כשהסוללה מתחת ל־80% והטלפון לא חם.
                • השאר את המסך פתוח ואל תשתמש בטלפון בזמן הבדיקה.
            """.trimIndent()))
            ring = LevelRing(context).apply { title = "מבחן טעינה"; subtitle = "60 שניות" }
            addView(ring)
            loadStatus = text("חבר את הכבל למטען ולחץ התחל", 15f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
            addView(loadStatus)
            addView(grid(listOf("lW" to "הספק כעת", "lAvg" to "ממוצע", "lMax" to "שיא", "lA" to "זרם לסוללה (שיא)",
                "lCharger" to "המטען מסוגל", "lStab" to "יציבות חיבור")))
            chart = Sparkline(context).apply { capacity = 60; color = 0xFF38BDF8.toInt(); unit = "W" }
            addView(chart, lp(top = 8f))
            loadBtn = button("▶  התחל מבחן טעינה", 0xFF16A34A.toInt(), 0xFF0891B2.toInt()) {
                if (running) stopLoad(aborted = true, reason = "הבדיקה בוטלה") else startLoad()
            }
            addView(loadBtn)
            resultText = text("", 15f, Color.WHITE, bold = true).apply { visibility = View.GONE; setPadding(0, dp(10f).toInt(), 0, 0) }
            addView(resultText)
        })

        // 2. Data test
        root.addView(card(0xFF2A1640.toInt(), 0xFF1A1A40.toInt()).apply {
            addView(title("2. האם הכבל מעביר נתונים (Data)?"))
            addView(small("""
                חבר את הכבל בין הטלפון ל**מחשב** (או לדיסק און קי / טלפון אחר).
                מטען קיר לא מתאים לבדיקה הזו – הוא לא משתמש בחוטי הנתונים.
            """.trimIndent().replace("**", "")))
            dataStatus = text("", 18f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10f).toInt(), 0, 0) }
            addView(dataStatus)
            dataDetail = small("")
            addView(dataDetail)
            dataSaveBtn = button("💾  שמור תוצאה לכבל הזה", 0xFF7C3AED.toInt(), 0xFF2563EB.toInt()) { saveData() }
            addView(dataSaveBtn)
        })

        // 3. Comparison
        root.addView(card(0xFF0F2E25.toInt(), 0xFF13283A.toInt()).apply {
            addView(title("3. השוואת הכבלים שלך"))
            addView(small("ממוין מהמהיר לאיטי. לחיצה ארוכה על כבל מוחקת אותו."))
            table = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(table, lp(top = 8f))
            addView(small("""
                * אנדרואיד לא חושף את הדירוג הרשמי של הכבל (למשל 5A/100W) ולא את ההתנגדות שלו.
                  המבחן מודד כמה הספק עובר בפועל עם המטען שלך – זה מה שקובע את מהירות הטעינה.
            """.trimIndent()))
        })

        return ScrollView(this).apply { addView(root); setBackgroundColor(0xFF0B1020.toInt()) }
    }

    private fun cableName(): String? {
        val n = nameInput.text.toString().trim()
        if (n.isEmpty()) { nameInput.error = "תן שם לכבל"; nameInput.requestFocus(); return null }
        return n
    }

    // ---------------------------------------------------------------- load test

    private fun startLoad() {
        val name = cableName() ?: return
        val s = reader.read()
        if (s == null || s.plugType == "לא מחובר") { loadStatus.text = "🔌 הכבל לא מחובר למטען"; loadStatus.setTextColor(0xFFFACC15.toInt()); return }
        if (s.isFull) { loadStatus.text = "הסוללה מלאה – אי אפשר למדוד. נסה כשהיא מתחת ל־80%"; return }
        samples.clear(); maxW = 0f; maxA = 0f; disconnects = 0; dips = 0
        wasPlugged = true; chargerMaxW = s.chargerMaxW; startLevel = s.levelPct.toInt()
        startedAt = SystemClock.elapsedRealtime(); running = true
        resultText.visibility = View.GONE
        loadBtn.text = "⏹  עצור"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RemoteLog.event(this, "מבחן כבל התחיל: '$name', סוללה ${startLevel}%, מטען ${s.plugType}, מקס ${s.chargerMaxW}")
    }

    private fun sample() {
        if (!running) return
        val s = reader.read() ?: return
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - startedAt) / 1000f
        val plugged = s.plugType != "לא מחובר"
        if (!plugged && wasPlugged) { disconnects++; unpluggedSince = now }
        if (!plugged && now - unpluggedSince > 10_000) { stopLoad(aborted = true, reason = "הכבל נותק ליותר מ־10 שניות"); return }
        wasPlugged = plugged

        val w = if (plugged) max(s.powerW ?: 0f, 0f) else 0f
        val a = if (plugged) max(s.currentA ?: 0f, 0f) else 0f
        s.chargerMaxW?.let { chargerMaxW = max(chargerMaxW ?: 0f, it) }
        chart.push(w)
        if (elapsed >= WARMUP_S && plugged) {
            val avgSoFar = if (samples.isEmpty()) w else samples.average().toFloat()
            if (samples.size >= 5 && w < avgSoFar * 0.4f) dips++
            samples += w
            maxW = max(maxW, w); maxA = max(maxA, a)
        }

        ring.level = (elapsed / DURATION_S * 100f).coerceAtMost(100f)
        ring.charging = plugged
        ring.subtitle = "נותרו ${max(0, (DURATION_S - elapsed).toInt())} שנ׳"
        ring.invalidate()
        loadStatus.text = if (elapsed < WARMUP_S) "⏳ ממתין שהמטען יתייצב…" else "⚡ מודד…"
        loadStatus.setTextColor(0xFF7DD3FC.toInt())
        set("lW", String.format("%.1f W", w), 0xFF38BDF8.toInt())
        set("lAvg", if (samples.isEmpty()) "…" else String.format("%.1f W", samples.average()))
        set("lMax", String.format("%.1f W", maxW), 0xFFF472B6.toInt())
        set("lA", String.format("%.2f A", maxA))
        set("lCharger", chargerMaxW?.let { String.format("%.0f W", it) } ?: "לא מדווח")
        set("lStab", stabilityText(), stabilityColor())

        if (elapsed >= DURATION_S) stopLoad(aborted = false, reason = null)
    }

    private fun stabilityText() = when {
        disconnects > 0 -> "⚠ התנתק $disconnects פעמים"
        dips > 2 -> "⚠ $dips נפילות הספק"
        else -> "✓ יציב"
    }
    private fun stabilityColor() = if (disconnects > 0 || dips > 2) 0xFFFACC15.toInt() else 0xFF4ADE80.toInt()

    private fun stopLoad(aborted: Boolean, reason: String?) {
        running = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadBtn.text = "▶  התחל מבחן טעינה"
        ring.charging = false
        if (aborted || samples.size < 10) {
            loadStatus.text = reason ?: "לא נאספו מספיק מדידות"
            loadStatus.setTextColor(0xFFFACC15.toInt())
            return
        }
        val name = nameInput.text.toString().trim()
        val avg = samples.average()
        val r = CableResult(name = name, avgW = avg, maxW = maxW.toDouble(), maxA = maxA.toDouble(),
            disconnects = disconnects, dips = dips, chargerMaxW = chargerMaxW?.toDouble(), levelAtTest = startLevel)
        store.save(r)
        RemoteLog.event(this, String.format("מבחן כבל הסתיים: '%s' ממוצע %.1fW שיא %.1fW %.2fA ניתוקים %d", name, avg, maxW, maxA, disconnects))

        val (grade, color) = CableStore.grade(avg)
        val notes = buildList {
            val cm = chargerMaxW
            if (cm != null && cm > 0 && avg < cm * 0.5 && startLevel < 80)
                add("• הכבל מעביר רק ${(avg / cm * 100).toInt()}% ממה שהמטען מסוגל – כנראה הכבל מגביל.")
            if (startLevel >= 80) add("• הסוללה הייתה מעל 80% – הטלפון מאט טעינה בכוונה, התוצאה נמוכה מהיכולת האמיתית.")
            if (disconnects > 0) add("• החיבור התנתק באמצע – מחבר רופף או כבל פגום.")
            else if (dips > 2) add("• היו נפילות הספק – ייתכן מגע לא יציב.")
        }
        loadStatus.text = "✓ הבדיקה הסתיימה"
        loadStatus.setTextColor(0xFF4ADE80.toInt())
        resultText.text = "$grade\n" + String.format("ממוצע %.1fW · שיא %.1fW · %.2fA", avg, maxW, maxA) +
            (if (notes.isNotEmpty()) "\n\n" + notes.joinToString("\n") else "")
        resultText.setTextColor(color)
        resultText.visibility = View.VISIBLE
        renderTable()
    }

    // ---------------------------------------------------------------- data test

    private fun refreshData() {
        val s = reader.read()
        val plugged = s != null && s.plugType != "לא מחובר"
        val now = SystemClock.elapsedRealtime()
        if (!plugged) pluggedSince = 0L else if (pluggedSince == 0L) pluggedSince = now

        val st = usbState
        val connected = st?.getBooleanExtra("connected", false) == true
        val configured = st?.getBooleanExtra("configured", false) == true
        val hostConnected = st?.getBooleanExtra("host_connected", false) == true
        val functions = st?.extras?.keySet()?.filter {
            it !in setOf("connected", "configured", "host_connected", "unlocked", "data_unlocked") && st.getBooleanExtra(it, false)
        }.orEmpty()
        val devices = runCatching { getSystemService(UsbManager::class.java).deviceList.values.toList() }.getOrDefault(emptyList())

        when {
            devices.isNotEmpty() -> {
                val d = devices.first()
                val ver = if (Build.VERSION.SDK_INT >= 23) d.version else "?"
                verdict(true, "✅ מעביר נתונים", "זוהה מכשיר USB: ${d.productName ?: d.deviceName} (USB $ver)")
            }
            connected || configured || hostConnected ->
                verdict(true, "✅ מעביר נתונים", "המחשב זיהה חיבור נתונים" +
                    (if (functions.isNotEmpty()) " (מצב: ${functions.joinToString()})" else ""))
            plugged && now - pluggedSince < 8_000 -> verdict(null, "⏳ בודק…", "ממתין שהמחשב יזהה את הטלפון")
            plugged && s?.plugType?.startsWith("USB") == true ->
                verdict(false, "⚡ טעינה בלבד", "מחובר למחשב אבל לא זוהה חיבור נתונים – הכבל כנראה לטעינה בלבד.\nודא שהמחשב דלוק ולא במצב שינה.")
            plugged -> verdict(null, "🔌 מחובר למטען", "כדי לבדוק נתונים חבר את הכבל למחשב, לא למטען קיר.")
            else -> verdict(null, "חבר את הכבל למחשב", "")
        }
    }

    private fun verdict(v: Boolean?, title: String, detail: String) {
        dataVerdict = v; dataVerdictText = detail
        dataStatus.text = title
        dataStatus.setTextColor(when (v) { true -> 0xFF4ADE80.toInt(); false -> 0xFFFACC15.toInt(); null -> Color.WHITE })
        dataDetail.text = detail
        dataSaveBtn.alpha = if (v == null) 0.4f else 1f
    }

    private fun saveData() {
        val v = dataVerdict ?: return
        val name = cableName() ?: return
        store.save(CableResult(name = name, data = v, dataDetail = dataVerdictText))
        val raw = usbState?.extras?.keySet()?.joinToString { "$it=${usbState?.extras?.get(it)}" }
        RemoteLog.event(this, "בדיקת נתונים: '$name' data=$v; usb_state: $raw")
        android.widget.Toast.makeText(this, "נשמר ✓", android.widget.Toast.LENGTH_SHORT).show()
        renderTable()
    }

    // ---------------------------------------------------------------- table

    private fun renderTable() {
        table.removeAllViews()
        val all = store.all()
        (nameInput.adapter as? ArrayAdapter<String>)?.let { it.clear(); it.addAll(all.map { c -> c.name }) }
        if (all.isEmpty()) { table.addView(small("עוד לא נבדקו כבלים.")); return }
        val df = SimpleDateFormat("d.M", Locale.getDefault())
        all.forEachIndexed { idx, c ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(0x14FFFFFF) }
                val p = dp(12f).toInt(); setPadding(p, p, p, p)
                setOnLongClickListener {
                    AlertDialog.Builder(this@CableTestActivity).setTitle("למחוק את \"${c.name}\"?")
                        .setPositiveButton("מחק") { _, _ -> store.delete(c.name); renderTable() }
                        .setNegativeButton("ביטול", null).show(); true
                }
                setOnClickListener { nameInput.setText(c.name); nameInput.dismissDropDown() }
            }
            val medal = when (idx) { 0 -> "🥇 "; 1 -> "🥈 "; 2 -> "🥉 "; else -> "" }
            row.addView(text(medal + c.name, 16f, Color.WHITE, bold = true))
            val (g, gc) = c.avgW?.let { CableStore.grade(it) } ?: ("מבחן טעינה לא בוצע" to 0x99FFFFFF.toInt())
            row.addView(text(g, 13f, gc))
            val parts = buildList {
                c.avgW?.let { add(String.format("%.1fW ממוצע", it)) }
                c.maxA?.let { add(String.format("%.2fA", it)) }
                add(when (c.data) { true -> "📶 מעביר נתונים"; false -> "⚡ טעינה בלבד"; null -> "נתונים: לא נבדק" })
                if ((c.disconnects ?: 0) > 0) add("⚠ לא יציב")
                add("נבדק ${df.format(Date(c.testedAt))}")
            }
            row.addView(text(parts.joinToString(" · "), 12f, 0xB3FFFFFF.toInt()))
            table.addView(row, lp(top = 6f))
        }
    }

    companion object {
        private const val DURATION_S = 60f
        private const val WARMUP_S = 8f
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
    }
}
