package com.mph1970.vacavision.overlayruntime.picker

import org.junit.Assert.*
import org.junit.Test

class PickerEntriesTest {
    @Test fun remotePresetsCannotRemoveOrDuplicateLocalSettings() {
        val weather = Preset("weather", "Weather", "/view-assist/weather")
        val replaced = PickerEntries.withSettings(listOf(weather, Preset(PickerEntries.SETTINGS_ID, "Wrong")))
        assertEquals(listOf(weather.id, PickerEntries.SETTINGS_ID), replaced.map { it.id })
        assertEquals("Settings", replaced.last().title)
        assertEquals(replaced, PickerEntries.withSettings(replaced))
        assertEquals(PickerEntries.SETTINGS_ID, PickerEntries.withSettings(emptyList()).single().id)
    }
}
