package com.silo.chargemonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionCode: Long, val versionName: String, val notes: String, val apkUrl: String)

/** Checks the GitHub "latest" release for a newer build and installs it. */
object Updater {
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 77
    private const val JOB_ID = 1001
    const val EXTRA_START_UPDATE = "start_update"
    const val ACTION_INSTALL_STATUS = "com.silo.chargemonitor.INSTALL_STATUS"
    const val RELEASE_PAGE = "https://github.com/silo226-cloud/BatteryCharger/releases/tag/latest"

    fun currentVersionCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    /** Blocking network call; run off the main thread. Returns null when up to date or offline. */
    fun fetchNewer(ctx: Context): UpdateInfo? = try {
        val base = BuildConfig.UPDATE_BASE_URL
        val json = JSONObject(httpGetText("$base/version.json?t=${System.currentTimeMillis()}"))
        val info = UpdateInfo(
            versionCode = json.getLong("versionCode"),
            versionName = json.optString("versionName"),
            notes = json.optString("notes"),
            apkUrl = "$base/${json.optString("apk", "ChargeMonitor.apk")}",
        )
        info.takeIf { it.versionCode > currentVersionCode(ctx) }
    } catch (_: Exception) {
        null
    }

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(5) {   // follow GitHub's redirects to its download CDN manually (cross-host)
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "ChargeMonitor-updater")
            }
            if (c.responseCode in 300..399) { u = URL(u, c.getHeaderField("Location")); c.disconnect() } else return c
        }
        error("too many redirects")
    }

    private fun httpGetText(url: String): String = open(url).let { c ->
        if (c.responseCode != 200) error("HTTP ${c.responseCode}")
        c.inputStream.bufferedReader().use { it.readText() }
    }

    // ---- install -------------------------------------------------------------

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettings(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /** Downloads the APK and hands it to the system installer (the user confirms with one tap). */
    fun downloadAndInstall(ctx: Context, info: UpdateInfo, progress: (Int) -> Unit) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            val conn = open(info.apkUrl)
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                session.openWrite("update.apk", 0, total).use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; progress(p) } }
                    }
                    session.fsync(out)
                }
            }
            val statusIntent = Intent(ctx, InstallStatusReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
            val pi = PendingIntent.getBroadcast(ctx, sessionId, statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            session.commit(pi.intentSender)
        }
    }

    // ---- background checks + notification -----------------------------------

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateCheckJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(6 * 60 * 60 * 1000L)          // every 6 hours
            .setPersisted(true)                          // survives reboot
            .build())
    }

    fun notifyIfNew(ctx: Context, info: UpdateInfo) {
        val prefs = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        if (prefs.getLong("notified", 0) >= info.versionCode) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "עדכוני גרסה", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_START_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (info.notes.isNotBlank()) info.notes else "הקש כדי לעדכן"
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("⚡ מד טעינה ${info.versionName} זמין")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text\nהקש כדי להוריד ולהתקין."))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }   // may be blocked if notifications are off
        prefs.edit().putLong("notified", info.versionCode).apply()
    }

    fun cancelNotification(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
}

/** Periodic background check. */
class UpdateCheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            Updater.fetchNewer(this)?.let { Updater.notifyIfNew(this, it) }
            jobFinished(params, false)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}

/** Receives the installer's result; asks the user to confirm when required, and reports failures. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) { fail(ctx, "מסך האישור של אנדרואיד לא התקבל"); return }
                try { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Exception) { fail(ctx, "אנדרואיד חסם את פתיחת מסך ההתקנה (${e.javaClass.simpleName})") }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.cancelNotification(ctx)
            PackageInstaller.STATUS_FAILURE_ABORTED -> fail(ctx, "ההתקנה בוטלה. לחץ שוב ובחר \"עדכן\" בחלון של אנדרואיד.")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> fail(ctx, "ההתקנה נחסמה (ייתכן ע\"י Play Protect). נסה \"הורדה ידנית\".")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                fail(ctx, "הגרסה החדשה לא מתאימה לגרסה המותקנת (חתימה שונה). הסר את האפליקציה והתקן מ\"הורדה ידנית\".")
            PackageInstaller.STATUS_FAILURE_STORAGE -> fail(ctx, "אין מספיק מקום פנוי בטלפון.")
            else -> fail(ctx, "ההתקנה נכשלה (קוד $status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}")
        }
    }

    private fun fail(ctx: Context, msg: String) {
        ctx.getSharedPreferences("updater", Context.MODE_PRIVATE).edit().putString("lastError", msg).apply()
        ctx.sendBroadcast(Intent(Updater.ACTION_INSTALL_STATUS + ".FAILED").setPackage(ctx.packageName).putExtra("msg", msg))
    }
}
