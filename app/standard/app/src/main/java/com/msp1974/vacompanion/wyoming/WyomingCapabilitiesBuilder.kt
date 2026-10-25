package com.msp1974.vacompanion.wyoming

import com.msp1974.vacompanion.device.DeviceManager
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject

class WyomingCapabilitiesBuilder @Inject constructor(deviceManager: DeviceManager) {

    val config = deviceManager.config
    val deviceInfo = deviceManager.deviceInfo

    @OptIn(ExperimentalSerializationApi::class)
    fun buildInfo(): JsonObject {
        val baseCapabilities = deviceInfo.getJson()

        return buildJsonObject {
           baseCapabilities.forEach { (key, value) -> put(key, value) }
            // Display-only build: voice and media are handled by another service on the device.
            // The VACA integration skips its voice, wake word, volume and media entities for this.
            put("display_only", true)
            put("protocol", "vaca-vision")
            put("protocol_version", 1)
            putJsonArray("wake_sounds") {}
            putJsonArray("alarms") {}
        }
    }
}