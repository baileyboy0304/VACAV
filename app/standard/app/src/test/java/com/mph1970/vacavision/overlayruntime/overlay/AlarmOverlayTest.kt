package com.mph1970.vacavision.overlayruntime.overlay

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.mph1970.vacavision.overlayruntime.ble.BleRemoteService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [25, 28])
class AlarmOverlayTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        context.getSharedPreferences("overlay_alarms", 0).edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(true)
    }
    @Test fun popupSurvivesTimeAndDismissesWithFireTvRemote() {
        val changes = mutableListOf<JSONObject>()
        AlarmOverlay(context) { changes.add(it) }.use { alarm ->
            alarm.action(JSONObject().put("id", "tea").put("activate", true).put("message", "Tea ready"))
            assertTrue(AlarmOverlay.active)
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = (Shadows.shadowOf(wm) as org.robolectric.shadows.ShadowWindowManagerImpl).views.last()
            assertEquals("Alarm popup", view.contentDescription)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(2))
            assertTrue(AlarmOverlay.active)
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            assertTrue(AlarmOverlay.active)
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            assertFalse(AlarmOverlay.active)
            assertFalse(changes.last().getBoolean("alarm"))
            assertEquals("tea", alarm.status().getJSONArray("dismissed_alarm_ids").getString(0))
            assertTrue(AlarmOverlay.blocksPicker)
        }
    }
    @Test fun bleDismissesOneAlarmAtATimeAndAcknowledgementSurvivesRestart() {
        AlarmOverlay(context) {}.use { alarm ->
            listOf("tea", "oven").forEach { alarm.action(JSONObject().put("id", it)) }
            val broadcast = LocalBroadcastManager.getInstance(context)
            broadcast.sendBroadcastSync(Intent(BleRemoteService.ACTION_BUTTON).putExtra(BleRemoteService.EXTRA_KEY, "next"))
            assertEquals(2, alarm.status().getInt("alarm_count"))
            broadcast.sendBroadcastSync(Intent(BleRemoteService.ACTION_BUTTON).putExtra(BleRemoteService.EXTRA_KEY, "select"))
            assertTrue(AlarmOverlay.active)
            assertEquals(1, alarm.status().getInt("alarm_count"))
            alarm.action(JSONObject().put("activate", false))
            assertFalse(AlarmOverlay.active)
        }
        AlarmOverlay(context) {}.use { alarm ->
            assertEquals(2, alarm.status().getJSONArray("dismissed_alarm_ids").length())
            alarm.action(JSONObject().put("id", "tea"))
            assertFalse(AlarmOverlay.active)
            alarm.acknowledge("tea")
            alarm.acknowledge("oven")
            assertEquals(0, alarm.status().getJSONArray("dismissed_alarm_ids").length())
        }
    }
}
