package com.mph1970.vacavision.overlayruntime.overlay

import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk=[25,28])
class DashboardRemoteTest {
    @org.junit.Before fun resetAlarmFixture() {
        org.robolectric.util.ReflectionHelpers.setStaticField(AlarmOverlay::class.java,"suppressPickerUntil",0L)
    }
    @Test fun remoteOwnsKeysOnlyUntilBackDismissalOrAutomaticReplacement() {
        val context=RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        val web=WebView(context)
        val controller=OverlayController.install(context,web)
        val field=OverlayController::class.java.getDeclaredField("host").apply { isAccessible=true }
        val host=field.get(controller) as OverlayHost
        fun focusable()=(host.root.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
        fun press(code:Int){host.root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code));host.root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code))}
        try {
            val media=mutableListOf<String>();controller.onRemoteTransport={media+=it}
            controller.navigateToPath("/view-assist/music",remote=true)
            assertTrue(focusable())
            press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            assertEquals(listOf("play_pause","next"),media)
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            assertFalse(host.isPickerVisible())
            press(KeyEvent.KEYCODE_BACK)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            assertTrue("Back must escape even an unresponsive dashboard",host.isPickerVisible())
            press(KeyEvent.KEYCODE_BACK)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertFalse(host.isPickerVisible());assertFalse(focusable())
            controller.navigateToPath("/view-assist/music",remote=true)
            controller.navigateToPath("/view-assist/weather")
            assertFalse("HA navigation must not retain remote capture",focusable())
            press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            assertEquals(2,media.size)
        } finally {controller.destroy();web.destroy()}
    }
    @Test fun heldTransportDoesNotToggleRepeatedlyAndBackWaitsForRelease() {
        assertFalse(DashboardRemoteKeys.fire(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK),"back"))
        assertTrue(DashboardRemoteKeys.fire(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK),"back"))
        assertFalse(DashboardRemoteKeys.fire(KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,3),"play_pause"))
        assertTrue(DashboardRemoteKeys.fire(KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,3),"right"))
    }
    @Test fun homeDismissesPickerAndDashboardAndReleasesRemoteFocus() {
        val context=RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        val web=WebView(context)
        val controller=OverlayController.install(context,web)
        val host=OverlayController::class.java.getDeclaredField("host").apply { isAccessible=true }.get(controller) as OverlayHost
        fun home() {
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason","homekey"))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertFalse(host.isPickerVisible());assertFalse(host.isOverlayExpanded())
            assertTrue((host.root.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        }
        try {
            controller.showPicker(remote=true);assertTrue(host.isPickerVisible());home()
            controller.navigateToPath("/view-assist/music",remote=true);assertTrue(host.isOverlayExpanded());home()
            controller.showPicker(remote=true);assertTrue("Picker must reopen after HOME",host.isPickerVisible());home()
        } finally { controller.destroy();web.destroy() }
    }
    @Test fun pickerSelectionRetainsItsInvocationSource() {
        val context=RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        com.mph1970.vacavision.overlayruntime.picker.DevicePickerStore(context).refresh(listOf(
            com.mph1970.vacavision.overlayruntime.picker.DiscoveredView("/view-assist/weather","Weather","mdi:weather-sunny",false)))
        val host=OverlayHost.create(context)!!
        val selections=mutableListOf<Boolean>()
        val picker=com.mph1970.vacavision.overlayruntime.picker.PickerController(context,host,{}, {},dashboardSelected={_,remote->selections+=remote})
        fun select(){
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).sendBroadcastSync(
                android.content.Intent(com.mph1970.vacavision.overlayruntime.ble.BleRemoteService.ACTION_BUTTON)
                    .putExtra(com.mph1970.vacavision.overlayruntime.ble.BleRemoteService.EXTRA_KEY,"select"))
        }
        try {
            picker.openFromUi(remote=false);select()
            picker.openFromUi(remote=true);select()
            assertEquals(listOf(false,true),selections)
        } finally {picker.dispose();host.destroy()}
    }
}
