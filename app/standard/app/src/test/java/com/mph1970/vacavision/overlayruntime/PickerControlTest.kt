package com.mph1970.vacavision.overlayruntime

import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import com.mph1970.vacavision.overlayruntime.overlay.OverlayHost
import com.mph1970.vacavision.overlayruntime.picker.PickerController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [25, 28])
class PickerControlTest {
    @org.junit.Before fun overlayMode() {
        RuntimeEnvironment.getApplication().getSharedPreferences("vacav_mode", 0).edit().putString("mode", "overlay").commit()
    }
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun togglesRealPickerAndReleasesFocusIncludingRapidRepeat() {
        ShadowSettings.setCanDrawOverlays(true)
        val host = OverlayHost.create(context)!!
        val picker = PickerController(context, host, {}, {})
        try {
            picker.toggleFromUi()
            assertTrue(host.isPickerVisible())
            picker.toggleFromUi()
            assertFalse(host.isPickerVisible())
            assertTrue((host.root.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            picker.toggleFromUi()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertTrue(host.isPickerVisible())
            picker.toggleFromUi()
            assertFalse(host.isPickerVisible())
            // External open must be reflected by the helper's next toggle.
            picker.openFromUi()
            picker.toggleFromUi()
            assertFalse(host.isPickerVisible())
        } finally { picker.dispose(); host.destroy() }
    }

    private fun withTimedPicker(timeout: Long? = null, test: (PickerController, OverlayHost) -> Unit) {
        ShadowSettings.setCanDrawOverlays(true)
        val prefs = context.getSharedPreferences("va_settings", 0)
        prefs.edit().remove("picker_idle_ms").commit()
        if (timeout != null) prefs.edit().putLong("picker_idle_ms", timeout).commit()
        val host = OverlayHost.create(context)!!
        val picker = PickerController(context, host, {}, {})
        try { test(picker, host) }
        finally { picker.dispose(); host.destroy(); prefs.edit().remove("picker_idle_ms").commit() }
    }
    private fun advance(ms: Long) = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun helperOpenExpiresAfterDefaultBleIdleTimeoutAndReleasesFocus() = withTimedPicker { picker, host ->
        picker.toggleFromUi()
        advance(4999)
        assertTrue(host.isPickerVisible())
        advance(301) // Include the existing dismissal animation.
        assertFalse(host.isPickerVisible())
        assertTrue((host.root.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
    }

    @Test fun helperOpenHonoursConfiguredTimeoutAndRemoteInputRestartsIt() = withTimedPicker(1000) { picker, host ->
        picker.toggleFromUi()
        advance(800)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcastSync(
            Intent(com.mph1970.vacavision.overlayruntime.ble.BleRemoteService.ACTION_BUTTON)
                .putExtra(com.mph1970.vacavision.overlayruntime.ble.BleRemoteService.EXTRA_KEY, "next"))
        advance(500)
        assertTrue("Input should extend the initial timeout", host.isPickerVisible())
        advance(800)
        assertFalse(host.isPickerVisible())
    }

    @Test fun closingThenReopeningGetsFreshTimeout() = withTimedPicker(1000) { picker, host ->
        picker.toggleFromUi()
        advance(800)
        picker.toggleFromUi()
        assertFalse(host.isPickerVisible())
        picker.toggleFromUi()
        advance(500)
        assertTrue("Old timeout must not close a reopened picker", host.isPickerVisible())
        advance(800)
        assertFalse(host.isPickerVisible())
    }

    @Test fun otherUiOpenAlsoStartsAndRefreshesTimeout() = withTimedPicker(1000) { picker, host ->
        picker.openFromUi()
        advance(800)
        picker.openFromUi()
        advance(500)
        assertTrue(host.isPickerVisible())
        advance(800)
        assertFalse(host.isPickerVisible())
    }
    @Test fun launcherDispatchesToggleThenFinishesWithoutSettings() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildActivity(PickerControlActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertEquals(OverlayService.TOGGLE_PICKER, Shadows.shadowOf(context).nextStartedService.action)
        controller.pause().stop().destroy()
    }

    @Test fun missingOverlayPermissionRoutesToSetupWithOpenIntent() {
        ShadowSettings.setCanDrawOverlays(false)
        val controller = Robolectric.buildActivity(PickerControlActivity::class.java).setup()
        val activity = controller.get()
        val setup: Intent = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, setup.component!!.className)
        assertTrue(setup.getBooleanExtra(MainActivity.OPEN_PICKER, false))
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}

