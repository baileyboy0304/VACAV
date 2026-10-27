package com.mph1970.vacavision.overlayruntime.picker

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

/** Local settings remain reachable even when HA replaces the dashboard preset list. */
object PickerEntries {
    const val SETTINGS_ID = "_vacaoverlay_settings"
    fun withSettings(presets: List<Preset>): List<Preset> = presets.filterNot { it.id == SETTINGS_ID } +
        Preset(id = SETTINGS_ID, title = "Settings", icon = "ic_overlay_settings")
}
