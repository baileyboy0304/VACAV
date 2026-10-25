package com.mph1970.vacavision.core

/** Shared role contract. Original work: MSP1974 (VACA), Dinki (View Assist). */
enum class VisionRole(val id: String, val label: String, val overlay: Boolean, val crown: Boolean, val available: Boolean = true) {
    OVERLAY_DISPLAY("overlay_display_only", "Overlay Display Only", true, false),
    STANDARD_DISPLAY("standard_display_only", "Standard Display Only", false, false),
    STANDARD_CROWN("standard_assist_sendspin", "Standard Display with assist and sendspin", false, true),
    STANDARD_UPSTREAM("standard_upstream_eda", "ES8 with upstream EDA", false, false),
    STANDARD_DOT("standard_dot_sendspin", "Display and Dot with sendspin", false, false),
    OVERLAY_ECHO_DOT("overlay_echo_dot", "Overlay Display and Dot with sendspin", true, false);

    companion object {
        fun supported(overlayBuild: Boolean) = values().filter { it.available && it.overlay == overlayBuild }
        fun resolve(id: String, overlayBuild: Boolean): VisionRole = supported(overlayBuild).firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Role is unavailable on this build")
    }
}
