package com.mph1970.vacavision.overlayruntime.settings

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.content.Context
import java.util.UUID

/** Deliberately small: only settings used by the overlay and player. */
class APPConfig private constructor(context: Context) {
    val prefs = context.applicationContext.getSharedPreferences("overlay_config", Context.MODE_PRIVATE)
    var uuid: String
        get() = prefs.getString("uuid", null) ?: UUID.randomUUID().toString().also { uuid = it }
        set(value) { prefs.edit().putString("uuid", value).apply() }
    var deviceName: String
        get() = prefs.getString("name", "VACA_Vision")!!
        set(value) { prefs.edit().putString("name", value).apply() }
    var haUrl: String
        get() = prefs.getString("ha_url", "")!!.ifBlank { DEFAULT_HA_URL }
        set(value) { prefs.edit().putString("ha_url", value.trimEnd('/')).apply() }
    var dashboard: String
        get() = prefs.getString("dashboard", "/view-assist/clock")!!
        set(value) { prefs.edit().putString("dashboard", value).apply() }
    var accessToken: String
        get() = prefs.getString("access_token", "")!!
        set(value) { prefs.edit().putString("access_token", value).apply() }
    var refreshToken: String
        get() = prefs.getString("refresh_token", "")!!
        set(value) { prefs.edit().putString("refresh_token", value).apply() }
    var sendspinUrl: String
        get() = prefs.getString("sendspin_url", "")!!
        set(value) { prefs.edit().putString("sendspin_url", value).apply() }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var outputDelayMs: Int
        get() = prefs.getInt("output_delay", 0)
        set(value) { prefs.edit().putInt("output_delay", value.coerceIn(0, 5000)).apply() }
    var sendspinEnabled: Boolean
        get() = prefs.getBoolean("sendspin_enabled", true)
        set(value) { prefs.edit().putBoolean("sendspin_enabled", value).apply() }
    var pauseFireTvMedia: Boolean
        get() = prefs.getBoolean("pause_fire_tv_media", true)
        set(value) { prefs.edit().putBoolean("pause_fire_tv_media", value).apply() }
    // 0 = off, 1 = stop/end only, 2 = pause or stop/end.
    var resumeFireTvMode: Int
        get() = prefs.getInt("resume_fire_tv_mode", 2).coerceIn(0, 2)
        set(value) { prefs.edit().putInt("resume_fire_tv_mode", value.coerceIn(0, 2)).apply() }
    var showClock: Boolean
        get() = prefs.getBoolean("show_clock", true)
        set(value) { prefs.edit().putBoolean("show_clock", value).apply() }
    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay_enabled", true)
        set(value) { prefs.edit().putBoolean("overlay_enabled", value).apply() }
    val needsSignIn: Boolean get() = accessToken.isBlank() && refreshToken.isBlank()
    companion object {
        const val DEFAULT_HA_URL = "http://192.168.1.129:8123"
        @Volatile private var instance: APPConfig? = null
        fun getInstance(context: Context): APPConfig = instance ?: synchronized(this) {
            instance ?: APPConfig(context).also { instance = it }
        }
    }
}
