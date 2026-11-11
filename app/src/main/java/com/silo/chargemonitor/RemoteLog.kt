package com.silo.chargemonitor

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Remote problem log: keeps a short trail of recent events (breadcrumbs) and files
 * crashes / update failures as GitHub Issues in the app's repository.
 *
 * Reports are queued on disk first, so a crash or a missing connection never loses them;
 * the queue is flushed on the next app start. Duplicate reports (same title and version)
 * are sent at most once per day, and at most [MAX_PER_DAY] reports go out per day.
 * Reports contain only app version, Android version, device model, events and stack traces.
 */
object RemoteLog {
    private const val PREFS = "remote_log"
    private const val MAX_TRAIL = 40
    private const val MAX_PER_DAY = 10
    private const val REPO = "silo226-cloud/BatteryCharger"

    val enabled get() = BuildConfig.ISSUES_TOKEN.isNotBlank()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }.format(Date())

    /** Record a breadcrumb (kept locally, attached to the next report). */
    @Synchronized
    fun event(ctx: Context, msg: String) {
        val p = prefs(ctx)
        val lines = p.getString("trail", "").orEmpty().lines().filter { it.isNotBlank() }.toMutableList()
        lines += "${now()} | ${msg.take(300)}"
        p.edit().putString("trail", lines.takeLast(MAX_TRAIL).joinToString("\n")).commit()
    }

    private fun trail(ctx: Context) = prefs(ctx).getString("trail", "").orEmpty()

    /** Queue a report and try to send it in the background. */
    fun report(ctx: Context, title: String, details: String, label: String) {
        enqueue(ctx, title, details, label)
        Thread { flush(ctx) }.start()
    }

    /** Queue only (used from the crash handler, where networking is unsafe). */
    @Synchronized
    fun enqueue(ctx: Context, title: String, details: String, label: String) {
        val p = prefs(ctx)
        val q = JSONArray(p.getString("queue", "[]"))
        if (q.length() >= 20) return
        val body = buildString {
            appendLine("**גרסה:** ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("**Android:** ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("**מכשיר:** ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("**זמן:** ${now()}")
            appendLine()
            appendLine("### פרטים")
            appendLine("```")
            appendLine(details.take(20_000))
            appendLine("```")
            appendLine("### אירועים אחרונים")
            appendLine("```")
            appendLine(trail(ctx))
            appendLine("```")
            appendLine("_דוח אוטומטי מהאפליקציה_")
        }
        q.put(JSONObject().put("title", "[${BuildConfig.VERSION_NAME}] ${title.take(120)}").put("body", body).put("label", label))
        p.edit().putString("queue", q.toString()).commit()
    }

    /** Send queued reports. Blocking; call off the main thread. */
    @Synchronized
    fun flush(ctx: Context) {
        if (!enabled) return
        val p = prefs(ctx)
        val q = JSONArray(p.getString("queue", "[]"))
        if (q.length() == 0) return
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        var sentToday = if (p.getString("day", "") == day) p.getInt("sentToday", 0) else 0
        val sentKeys = JSONObject(p.getString("sentKeys", "{}"))
        val remaining = JSONArray()
        for (i in 0 until q.length()) {
            val item = q.getJSONObject(i)
            val key = item.getString("title")
            if (sentKeys.optString(key) == day) continue               // same problem already reported today
            if (sentToday >= MAX_PER_DAY) { remaining.put(item); continue }
            if (post(item)) { sentToday++; sentKeys.put(key, day) } else remaining.put(item)
        }
        // forget dedupe keys from previous days
        val keep = JSONObject(); sentKeys.keys().forEach { if (sentKeys.optString(it) == day) keep.put(it, day) }
        p.edit().putString("queue", remaining.toString()).putString("day", day)
            .putInt("sentToday", sentToday).putString("sentKeys", keep.toString()).commit()
    }

    private fun post(item: JSONObject): Boolean = try {
        val c = (URL("https://api.github.com/repos/$REPO/issues").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${BuildConfig.ISSUES_TOKEN}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "ChargeMonitor-app")
        }
        val json = JSONObject().put("title", item.getString("title")).put("body", item.getString("body"))
            .put("labels", JSONArray().put("auto-report").put(item.getString("label")))
        c.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        c.disconnect()
        code in 200..299 || code == 422   // 422 = bad payload; drop it rather than retry forever
    } catch (_: Exception) {
        false
    }
}
