package com.mph1970.vacavision.overlayruntime.picker

import com.msp1974.vacompanion.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [25, 28])
class PickerIconResourceTest {
    private val repository get() = PresetRepository(RuntimeEnvironment.getApplication())

    @Test fun settingsEntryLoadsCogFromMergedResources() {
        val settings = PickerEntries.withSettings(emptyList()).single()
        assertEquals(R.drawable.ov_ic_overlay_settings, shadowOf(repository.loadIcon(settings)).createdFromResId)
    }

    @Test fun savedLegacyAndPrefixedNamesLoadTheSameDashboardIcon() {
        for (name in listOf("ic_dashboard_weather", "ov_ic_dashboard_weather")) {
            assertEquals(R.drawable.ov_ic_dashboard_weather,
                shadowOf(repository.loadIcon(Preset("weather", "Weather", icon = name))).createdFromResId)
        }
    }

    @Test fun unknownResourceStillUsesThePlaceholder() {
        assertEquals(R.drawable.ov_ic_dashboard_placeholder,
            shadowOf(repository.loadIcon(Preset("unknown", "Unknown", icon = "missing_icon"))).createdFromResId)
    }
}
