package com.mph1970.vacavision.overlayruntime.overlay

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import com.mph1970.vacavision.overlayruntime.settings.APPConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Use HA's named timezone, including DST, without changing Android's system clock. */
class HaClock(private val config: APPConfig) {
    fun rememberTimeZone(origin: String, zoneId: String): Boolean {
        // TimeZone.getTimeZone silently returns GMT for invalid IDs. Keep the last good value.
        if (origin != config.haUrl || zoneId !in zoneIds) return false
        config.prefs.edit().putString("clock_ha_origin", origin)
            .putString("clock_ha_zone", zoneId).apply()
        return true
    }

    fun format(nowMillis: Long = System.currentTimeMillis()): String {
        val zoneId = config.prefs.getString("clock_ha_zone", null)
            .takeIf { config.prefs.getString("clock_ha_origin", null) == config.haUrl }
        val zone = TimeZone.getTimeZone(if (zoneId in zoneIds) zoneId else DEFAULT_TIME_ZONE)
        return SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = zone }.format(Date(nowMillis))
    }

    companion object {
        // This TV installation is in the UK. HA can override this once reachable.
        // Never fall back to the Cube's GMT setting, which omits British Summer Time.
        const val DEFAULT_TIME_ZONE = "Europe/London"
        private val zoneIds = TimeZone.getAvailableIDs().toSet()
    }
}
