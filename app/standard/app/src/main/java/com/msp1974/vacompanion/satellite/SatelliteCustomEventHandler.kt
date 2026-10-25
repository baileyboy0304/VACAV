package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.WebViewGestureDetector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds


class SatelliteCustomEventHandler(
    val context: Context,
    val deviceManager: DeviceManager,
    val scope: CoroutineScope,
    val satellite: Satellite
): EventListener {

    val config = deviceManager.config

    private val serviceStarted = CompletableDeferred<Int>()

    fun run() {
        scope.launch {
            start()
        }
        Timber.d("Satellite custom event handler started")
    }

    suspend fun start() {
        withContext(Dispatchers.Default) {
            val job = launch {
                try {
                    config.eventBroadcaster.addListener(this@SatelliteCustomEventHandler)
                    awaitCancellation()
                } finally {
                    config.eventBroadcaster.removeListener(this@SatelliteCustomEventHandler)
                }
            }


            serviceStarted.await()
            job.cancel()
            Timber.d("Satellite custom event handler stopped")
        }
    }

    fun stop() {
        serviceStarted.complete(0)
    }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        when (event.eventName) {
            "screenSaver" -> {
                satellite.sendSetting("screen_saver", event.newValue)
            }
            "screenSaverDisableOnTouch" -> {
                satellite.sendSetting("screen_saver_disable_on_touch", event.newValue)
            }
            "currentPath" -> {
                satellite.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("current_path", event.newValue.toString())
                        })
                    }
                )
            }
            "screenOn" -> {
                val isOn = event.newValue as Boolean
                satellite.sendSetting("screen_on", isOn)
                if (isOn && config.enableMotionDetection) {
                    // Force restart of camera when screen turns on to ensure recovery
                    scope.launch {
                        delay(500.milliseconds)
                        satellite.motionTask.startCamera()
                    }
                }
            }
            "motionDetectionMode" -> {
                val mode = event.newValue as String
                if (mode != "none") {
                    if (!config.cameraStreamActive) {
                        satellite.motionTask.startCamera()
                    }
                } else {
                    scope.launch { satellite.motionTask.stopCamera() }
                }
            }
            "motion" -> {
                val value = event.newValue as? Boolean ?: true
                config.lastMotion = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                satellite.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("motion_detected", value)
                            if (value) {
                                put("last_motion", config.lastMotion)
                            }
                        })
                    }
                )
            }
            "lastActivity" -> {
                satellite.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("last_activity", config.lastActivity)
                        })
                    }
                )
            }
            "cameraStreamActive" -> {
                val active = event.newValue as Boolean
                if (active) {
                    scope.launch { satellite.motionTask.stopCamera() }
                } else if (config.enableMotionDetection) {
                    satellite.motionTask.startCamera()
                }
            }
            "gesture" -> {
                val data = event.newValue as? WebViewGestureDetector.GestureEvent
                if (data != null) {
                    satellite.sendCustomEvent("gesture",
                        buildJsonObject {
                            put("gesture", data.direction.toString().lowercase()  )
                            put("touch_points", data.pointers)
                            put("x", data.startX)
                            put("y", data.startX)
                        }
                    )
                }
            }
            "proximity" -> {
                val value = event.newValue as? Float
                if (value != null) {
                    satellite.sendCustomEvent("proximity",
                        buildJsonObject {
                            put("proximity", value)
                            put("near", value == 0f)
                        }
                    )
                }
            }
            else -> consumed = false
        }
        if (consumed) {
            Timber.d("Event: ${event.eventName} - ${event.newValue}")
        }
    }
}