package com.msp1974.vacompanion

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [25, 28])
class DeviceModeTest {
    @Test fun fireTvDefaultsToOverlayAndEs8KeepsStandard() {
        assertEquals("overlay", DeviceMode.defaultMode("Amazon", "AFTKA"))
        assertEquals("standard", DeviceMode.defaultMode("Amazon", "AEOCW"))
        assertEquals("standard", DeviceMode.defaultMode("Meta", "Portal+"))
    }
    @Test fun installerSelectionPersistsAndInvalidSelectionCannotChangeIt() {
        val context = RuntimeEnvironment.getApplication()
        DeviceMode.accept(context, Intent().putExtra(DeviceMode.EXTRA, "overlay"))
        assertTrue(DeviceMode.isOverlay(context))
        DeviceMode.accept(context, Intent().putExtra(DeviceMode.EXTRA, "invalid"))
        assertTrue(DeviceMode.isOverlay(context))
        DeviceMode.accept(context, Intent().putExtra(DeviceMode.EXTRA, "standard"))
        assertFalse(DeviceMode.isOverlay(context))
    }
    @Test fun capabilitiesExposeOnlyActiveRolesButIncludeBothRuntimeModes() {
        val overlay = com.mph1970.vacavision.core.VacaProtocol.capabilities("Fire TV", 25, "test", true)
        val standard = com.mph1970.vacavision.core.VacaProtocol.capabilities("ES8", 25, "test", false)
        assertTrue(overlay.getBoolean("unified_apk"))
        assertEquals("overlay", overlay.getString("device_mode"))
        assertTrue(overlay.getJSONArray("roles").toString().contains("overlay_echo_dot"))
        assertFalse(standard.getJSONArray("roles").toString().contains("overlay_echo_dot"))
        assertTrue(standard.getJSONArray("roles").toString().contains("standard_upstream_eda"))
        assertFalse(standard.getBoolean("native_audio"))
    }
}
