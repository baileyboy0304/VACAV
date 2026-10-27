package com.mph1970.vacavision.overlayruntime.overlay

import android.view.View
import android.view.WindowManager
import com.mph1970.vacavision.overlayruntime.picker.DevicePickerStore
import com.mph1970.vacavision.overlayruntime.picker.DashboardViews
import com.mph1970.vacavision.overlayruntime.settings.APPConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [25, 28])
class OverlayLayoutTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        APPConfig.getInstance(context).prefs.edit().clear().commit()
        context.filesDir.listFiles()?.filter { it.name.startsWith("picker-") }?.forEach { it.delete() }
    }
    @Test fun anchorsResolveIndependentlyAndFreeAxesKeepCoordinates() {
        val layout = OverlayLayout(.4f, .3f, .2f, .1f, HorizontalAnchor.FREE, VerticalAnchor.FREE)
        assertEquals(OverlayLayout.Bounds(384, 108, 768, 324), layout.bounds(1920, 1080))
        assertEquals(1152, layout.copy(horizontal = HorizontalAnchor.RIGHT).bounds(1920,1080).x)
        assertEquals(576, layout.copy(horizontal = HorizontalAnchor.MIDDLE).bounds(1920,1080).x)
        assertEquals(0, layout.copy(horizontal = HorizontalAnchor.LEFT).bounds(1920,1080).x)
        assertEquals(0, layout.copy(vertical = VerticalAnchor.TOP).bounds(1920,1080).y)
        assertEquals(378, layout.copy(vertical = VerticalAnchor.MIDDLE).bounds(1920,1080).y)
        assertEquals(756, layout.copy(vertical = VerticalAnchor.BOTTOM).bounds(1920,1080).y)
    }
    @Test fun movingAnchoredWindowStartsAtVisiblePositionAndReleasesOnlyMovedAxis() {
        val moved = OverlayLayout(.5f,.5f, horizontal = HorizontalAnchor.RIGHT, vertical = VerticalAnchor.BOTTOM).move(-.01f,0f)
        assertEquals(.49f, moved.left(), .00001f)
        assertEquals(.5f, moved.top(), .00001f)
        assertEquals(HorizontalAnchor.FREE, moved.horizontal)
        assertEquals(VerticalAnchor.BOTTOM, moved.vertical)
    }
    @Test fun resizeHonoursGravityAndClampsFreePositionWithinScreen() {
        val anchored = OverlayLayout(.4f,.3f, horizontal=HorizontalAnchor.RIGHT, vertical=VerticalAnchor.BOTTOM).resize(.1f,.1f)
        assertEquals(.5f, anchored.left(), .00001f)
        assertEquals(.6f, anchored.top(), .00001f)
        val free = OverlayLayout(.2f,.2f,.8f,.8f,HorizontalAnchor.FREE,VerticalAnchor.FREE).resize(.4f,.4f)
        assertEquals(.4f, free.left(), .00001f)
        assertEquals(.4f, free.top(), .00001f)
        assertEquals(.1f, free.resize(-2f,-2f).width, 0f)
        assertEquals(1f, free.resize(2f,2f).height, 0f)
    }
    @Test fun selectingFreePreservesVisibleCoordinates() {
        val layout = OverlayLayout(.4f,.4f, horizontal=HorizontalAnchor.LEFT, vertical=VerticalAnchor.BOTTOM)
        assertEquals(0f, layout.cycleHorizontal().left(),0f)
        assertEquals(HorizontalAnchor.FREE,layout.cycleHorizontal().horizontal)
        assertEquals(.6f, layout.cycleVertical().top(),.00001f)
        assertEquals(VerticalAnchor.FREE,layout.cycleVertical().vertical)
    }
    @Test fun exactPathLookupAndPopulationRetainLayoutWithoutLeakingToAnotherView() {
        val store=DevicePickerStore(context)
        store.refresh(DashboardViews.parse(JSONObject("""{"views":[{"path":"weather"},{"path":"weather-detail"}]}""")))
        val chosen=OverlayLayout(.45f,.35f,.3f,.2f,HorizontalAnchor.FREE,VerticalAnchor.FREE,15000)
        store.saveLayout("/view-assist/weather-detail",chosen)
        assertEquals(chosen, store.layout("http://ha:8123/view-assist/weather-detail?external_auth=1"))
        assertEquals(1f, store.layout("/view-assist/weather").width,0f)
        store.refresh(DashboardViews.parse(JSONObject("""{"views":[{"path":"weather-detail","title":"Renamed"}]}""")))
        assertEquals(chosen,DevicePickerStore(context).layout("/view-assist/weather-detail"))
        assertTrue(runCatching { store.saveLayout("/view-assist/removed",chosen) }.isFailure)
    }
    @Test fun windowHostRetainsFreePositionAfterBoundsRecheckAndEditorSuspension() {
        Shadows.shadowOf(context).setSystemService(android.content.Context.WINDOW_SERVICE, context.getSystemService(android.content.Context.WINDOW_SERVICE))
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val host=OverlayHost.create(context)!!
        try {
            val layout=OverlayLayout(.4f,.3f,.2f,.1f,HorizontalAnchor.FREE,VerticalAnchor.FREE,0)
            host.webViewContainer=View(context)
            host.showOverlay(layout)
            val before=(host.root.layoutParams as WindowManager.LayoutParams).let { listOf(it.x,it.y,it.width,it.height) }
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(before,(host.root.layoutParams as WindowManager.LayoutParams).let { listOf(it.x,it.y,it.width,it.height) })
            host.setEditorHidden(true)
            host.showOverlay(layout); host.shrinkToBubble(); host.showPicker(View(context))
            assertEquals(View.GONE,host.root.visibility)
            host.setEditorHidden(false)
            assertEquals(View.VISIBLE,host.root.visibility)
            host.showOverlay(layout)
            assertEquals(before,(host.root.layoutParams as WindowManager.LayoutParams).let { listOf(it.x,it.y,it.width,it.height) })
        } finally { host.destroy() }
    }
    @Test fun originalDurationsAndCameraDefaultsAreUsed() {
        mapOf("weather" to 10000L, "tides" to 10000L, "thermostat" to 10000L,
            "music" to 0L, "newmusic" to 0L, "find" to 0L, "locate" to 15000L,
            "clock" to 100L, "camera" to 30000L, "cameras" to 30000L,
            "webcams" to 30000L, "front_camera" to 30000L, "random" to 10000L).forEach { (view, duration) ->
            assertEquals(view, duration, OverlayLayout.defaults("/view-assist/$view").durationMs)
        }
    }
    @Test fun oldImplicitDurationsMigrateButExplicitEditsAndGeometrySurviveRefresh() {
        val store=DevicePickerStore(context)
        val views=DashboardViews.parse(JSONObject("""{"views":[{"path":"music"},{"path":"webcams"}]}"""))
        store.refresh(views)
        val music=OverlayLayout(.4f,.3f,.2f,.1f,HorizontalAnchor.FREE,VerticalAnchor.FREE,15000)
        store.saveLayout("/view-assist/music",music)
        store.saveLayout("/view-assist/webcams",music.copy(durationMs=10000))
        assertEquals(music.copy(durationMs=0),store.layout("/view-assist/music"))
        assertEquals(30000L,store.layout("/view-assist/webcams").durationMs)
        store.saveLayout("/view-assist/music",music,durationEdited=true)
        store.saveLayout("/view-assist/webcams",music.copy(durationMs=0),durationEdited=true)
        store.refresh(views)
        assertEquals(music,store.layout("/view-assist/music"))
        assertEquals(0L,store.layout("/view-assist/webcams").durationMs)
        store.saveLayout("/view-assist/music",music.copy(width=.6f))
        assertEquals(15000L,store.layout("/view-assist/music").durationMs)
    }
    @Test fun timedOverlayHidesButPersistentReplacementCancelsTheOldTimeout() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val host=OverlayHost.create(context)!!
        try {
            host.webViewContainer=View(context)
            host.setOverlayFadeDurations(0,0)
            val main=Shadows.shadowOf(android.os.Looper.getMainLooper())
            host.showOverlay(OverlayLayout(durationMs=1000))
            main.idleFor(Duration.ofMillis(500))
            assertTrue(host.isOverlayExpanded())
            host.showOverlay(OverlayLayout(durationMs=0))
            main.idleFor(Duration.ofSeconds(60))
            assertTrue(host.isOverlayExpanded())
            host.showOverlay(OverlayLayout(durationMs=1000))
            main.idleFor(Duration.ofMillis(1500))
            assertFalse(host.isOverlayExpanded())
        } finally { host.destroy() }
    }
}
