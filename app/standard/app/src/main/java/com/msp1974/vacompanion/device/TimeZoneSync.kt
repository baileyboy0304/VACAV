package com.msp1974.vacompanion.device

import android.app.AlarmManager
import android.content.Context
import com.msp1974.vacompanion.device.authentication.AuthenticationManager
import com.msp1974.vacompanion.device.authentication.HttpClientProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.util.TimeZone

/**
 * Sets the device time zone to Home Assistant's configured time zone.
 *
 * Devices without working network time zone detection (e.g. Fire OS on an Echo Show, where
 * persist.sys.timezone is unset) otherwise run on UTC, so dashboards show the wrong local time.
 * SET_TIME_ZONE is a normal (install-time) permission on these devices; if a firmware rejects it
 * the device time zone is simply left unchanged.
 */
class TimeZoneSync(
    private val context: Context,
    private val authenticationManager: AuthenticationManager,
    private val ignoreSslErrors: () -> Boolean,
) {
    suspend fun sync() {
        try {
            val timeZone = fetchHomeAssistantTimeZone() ?: return
            if (TimeZone.getDefault().id == timeZone) return
            if (timeZone !in TimeZone.getAvailableIDs()) {
                Timber.w("Home Assistant time zone $timeZone is not known to this device")
                return
            }
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setTimeZone(timeZone)
            Timber.i("Device time zone set to $timeZone from Home Assistant")
        } catch (e: Exception) {
            Timber.w("Unable to sync time zone from Home Assistant: ${e.message}")
        }
    }

    private suspend fun fetchHomeAssistantTimeZone(): String? {
        val url = "${authenticationManager.getBaseUrl()}/api/config"
        HttpClientProvider(ignoreSslErrors).get().use { client ->
            val response = client.get(url) {
                header(HttpHeaders.Authorization, authenticationManager.buildBearerToken())
            }
            if (!response.status.isSuccess()) {
                Timber.w("Home Assistant /api/config returned ${response.status}")
                return null
            }
            return Json.parseToJsonElement(response.bodyAsText())
                .jsonObject["time_zone"]?.jsonPrimitive?.content
        }
    }
}
