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

    @Test fun powerBankActivityStarts() {
        val c = Robolectric.buildActivity(PowerBankActivity::class.java).setup()
        val v = c.get().window.decorView
        v.measure(1080, 2400); v.layout(0, 0, 1080, 2400)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        c.pause().stop().destroy()
    }
}
