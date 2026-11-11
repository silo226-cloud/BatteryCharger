package com.silo.chargemonitor

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/** Installs a crash handler that shows the error on screen instead of silently closing. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!isCrashProcess()) {
            val p = getSharedPreferences("remote_log", MODE_PRIVATE)
            val last = p.getInt("lastVersionCode", 0)
            if (last != BuildConfig.VERSION_CODE) {
                RemoteLog.event(this, if (last == 0) "הותקנה גרסה ${BuildConfig.VERSION_NAME}"
                    else "עודכן מקוד $last לגרסה ${BuildConfig.VERSION_NAME}")
                p.edit().putInt("lastVersionCode", BuildConfig.VERSION_CODE).apply()
            }
            RemoteLog.event(this, "האפליקציה נפתחה")
            Thread { RemoteLog.flush(this) }.start()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val report = "גרסה ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}\n\n$sw"
                val where = e.stackTrace.firstOrNull { it.className.startsWith("com.silo") }
                    ?.let { " @ ${it.fileName}:${it.lineNumber}" } ?: ""
                RemoteLog.enqueue(this, "קריסה: ${e.javaClass.simpleName}$where", sw.toString(), "crash")
                startActivity(Intent(this, CrashActivity::class.java)
                    .putExtra("report", report)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                Process.killProcess(Process.myPid()); exitProcess(10)
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, e)
            }
        }
    }

    private fun isCrashProcess() =
        (if (Build.VERSION.SDK_INT >= 28) getProcessName() else "").endsWith(":crash")
}

/** Runs in its own process, so it still works after the main app crashed. */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra("report") ?: "שגיאה לא ידועה"
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad); setBackgroundColor(0xFF0B1020.toInt())
        }
        root.addView(TextView(this).apply {
            text = if (RemoteLog.enabled) "😕 האפליקציה נתקלה בשגיאה\nהדוח נשלח אוטומטית לפיתוח ✓"
                   else "😕 האפליקציה נתקלה בשגיאה\nצלם את המסך או לחץ \"העתק\" ושלח לפיתוח"
            textSize = 18f; setTextColor(Color.WHITE)
        })
        root.addView(Button(this).apply {
            text = "העתק את פרטי השגיאה"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("crash", report))
                text = "הועתק ✓"
            }
        })
        root.addView(TextView(this).apply {
            text = report; textSize = 11f; setTextColor(0xFFFCA5A5.toInt()); setTextIsSelectable(true)
            textDirection = TextView.TEXT_DIRECTION_LTR
        })
        Thread { RemoteLog.flush(this) }.start()
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(0xFF0B1020.toInt()) })
    }
}
