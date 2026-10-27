package com.mph1970.vacavision.overlayruntime.picker

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import com.mph1970.vacavision.overlayruntime.overlay.OverlayLayout

enum class DashboardLoading(val value: String, val label: String, val description: String) {
    SELECTED("selected", "When selected", "Load when opened. Lowest background resource use."),
    PICKER("picker", "When picker opens", "Prepare when the picker opens. Camera streams stop on dismissal."),
    KEEP_ALIVE("keep_alive", "Keep alive", "Keep loaded with live updates. Cameras keep streaming in the background.");

    companion object {
        fun default(path: String) = when (OverlayLayout.viewName(path)) {
            "weather", "tides" -> KEEP_ALIVE
            "beachcam" -> PICKER
            else -> SELECTED
        }
        fun parse(value: String?, path: String) = entries.firstOrNull { it.value == value } ?: default(path)
    }
}
