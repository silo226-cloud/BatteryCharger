package com.silo.chargemonitor

import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31, 33, 34, 35])
class LaunchTest {
    @Test fun mainActivityStartsAndRefreshes() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        ctx.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 55); putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
            putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
            putExtra(BatteryManager.EXTRA_VOLTAGE, 4100); putExtra(BatteryManager.EXTRA_TEMPERATURE, 320)
        })
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val sbm = org.robolectric.Shadows.shadowOf(bm)
        sbm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -1_850_000)
        sbm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, -1_700_000)
        sbm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, 2_400_000)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        repeat(130) { org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS) }
        val v = c.get().window.decorView
        v.measure(1080, 2400); v.layout(0, 0, 1080, 2400)
        v.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1080, 2400, android.graphics.Bitmap.Config.ARGB_8888)))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(5, java.util.concurrent.TimeUnit.SECONDS)
        c.pause().stop().destroy()
    }

    @Test fun cableTestRunsAndSaves() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        ctx.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 40); putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
            putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
            putExtra(BatteryManager.EXTRA_VOLTAGE, 4000); putExtra(BatteryManager.EXTRA_TEMPERATURE, 310)
            putExtra("max_charging_current", 3_000_000); putExtra("max_charging_voltage", 9_000_000)
        })
        org.robolectric.Shadows.shadowOf(ctx.getSystemService(BatteryManager::class.java))
            .setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -5_000_000)
        val c = Robolectric.buildActivity(CableTestActivity::class.java).setup()
        val a = c.get()
        a.findViewById<android.view.View>(android.R.id.content)
        val input = a.window.decorView.findViewsByType(android.widget.AutoCompleteTextView::class.java).first()
        input.setText("כבל בדיקה")
        // press "start" (first clickable TextView containing the start label)
        a.window.decorView.findViewsByType(android.widget.TextView::class.java)
            .first { it.text.toString().contains("התחל מבחן טעינה") }.performClick()
        repeat(65) { org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS) }
        val saved = CableStore(ctx).get("כבל בדיקה")
        org.junit.Assert.assertNotNull(saved)
        org.junit.Assert.assertEquals(20.0, saved!!.avgW!!, 0.5)   // 4.0 V x 5 A
        c.pause().stop().destroy()
    }

    private fun <T : android.view.View> android.view.View.findViewsByType(cls: Class<T>): List<T> {
        val out = ArrayList<T>()
        fun walk(v: android.view.View) {
            if (cls.isInstance(v)) out += cls.cast(v)!!
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this); return out
    }

    @Test fun backAsksBeforeClosing() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        @Suppress("DEPRECATION") a.onBackPressed()
        val dlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        org.junit.Assert.assertNotNull("exit confirmation dialog", dlg)
        org.junit.Assert.assertFalse("activity must not close before confirming", a.isFinishing)
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        org.junit.Assert.assertTrue("activity closes after confirming", a.isFinishing)
        org.junit.Assert.assertFalse(MonitorService.isEnabled(a))
    }

    @Test fun wirelessPadTestAndPowerShare() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        fun battery(plugged: Int, status: Int) = ctx.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 50); putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_STATUS, status); putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
            putExtra(BatteryManager.EXTRA_VOLTAGE, 4000); putExtra(BatteryManager.EXTRA_TEMPERATURE, 330)
        })
        val bm = org.robolectric.Shadows.shadowOf(ctx.getSystemService(BatteryManager::class.java))
        battery(BatteryManager.BATTERY_PLUGGED_WIRELESS, BatteryManager.BATTERY_STATUS_CHARGING)
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -2_500_000)   // 10 W
        val c = Robolectric.buildActivity(WirelessActivity::class.java).setup()
        val a = c.get()
        val views = a.window.decorView.findViewsByType(android.widget.TextView::class.java)
        a.window.decorView.findViewsByType(android.widget.AutoCompleteTextView::class.java).first().setText("משטח")
        views.first { it.text.toString().contains("התחל מבחן משטח") }.performClick()
        repeat(65) { org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS) }
        val pad = CableStore(ctx, "pads").get("משטח · עם כיסוי")
        org.junit.Assert.assertNotNull(pad)
        org.junit.Assert.assertEquals(10.0, pad!!.avgW!!, 0.3)

        // PowerShare: 1 W baseline drain, then 4 W while sharing -> ~3 W given out
        battery(0, BatteryManager.BATTERY_STATUS_DISCHARGING)
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, 250_000)
        a.window.decorView.findViewsByType(android.widget.TextView::class.java)
            .first { it.text.toString().contains("התחל מדידת PowerShare") }.performClick()
        repeat(31) { org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS) }
        bm.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, 1_000_000)
        repeat(20) { org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS) }
        val out = a.window.decorView.findViewsByType(android.widget.TextView::class.java)
            .map { it.text.toString() }.first { it.startsWith("≈") }
        org.junit.Assert.assertTrue("estimate shown: $out", out.contains("3.0"))
        c.pause().stop().destroy()
    }

    @Test fun powerBankActivityStarts() {
        val c = Robolectric.buildActivity(PowerBankActivity::class.java).setup()
        val v = c.get().window.decorView
        v.measure(1080, 2400); v.layout(0, 0, 1080, 2400)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        c.pause().stop().destroy()
    }
}
