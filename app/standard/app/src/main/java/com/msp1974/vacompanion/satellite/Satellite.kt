package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.device.Camera
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.SoundControl.Companion.isDoNotDisturbEnabled
import com.msp1974.vacompanion.wyoming.EVENT_TYPE
import com.msp1974.vacompanion.wyoming.SatelliteState
import com.msp1974.vacompanion.wyoming.WyomingCapabilitiesBuilder
import com.msp1974.vacompanion.wyoming.WyomingCustomEventType
import com.msp1974.vacompanion.wyoming.WyomingEvent
import com.msp1974.vacompanion.wyoming.WyomingPacket
import io.github.z4kn4fein.semver.toVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

interface ISatelliteEvent {
    fun onEvent(event: String, data: JsonObject)
    fun sendSatelliteMessage(clientId: String, type: String, data: JsonObject, payload: ByteArray = ByteArray(0))
}

/**
 * The per-connection session with the Home Assistant VACA integration.
 *
 * This build is a display-only device: voice (wake word, mic streaming, TTS) and media playback
 * are handled by a separate service on the device, so audio-related Wyoming events from HA are
 * ignored. The session carries settings, device status/sensors, camera/motion and display actions.
 */
abstract class Satellite(var context: Context, val deviceManager: DeviceManager, val scope: CoroutineScope, clientIdString: String): ISatelliteEvent, EventListener {

    val config = deviceManager.config
    val deviceInfo = deviceManager.deviceInfo

    var clientId = clientIdString

    var sensorJob: Job? = null
    var motionTask = Camera(context, config)

    private val eventHandler = SatelliteCustomEventHandler(context, deviceManager, scope, this)

    private var _satelliteState = MutableStateFlow(SatelliteState.STOPPED)
    val satelliteState: StateFlow<SatelliteState> = _satelliteState.asStateFlow()

    // WyomingServerStatus.satelliteRunning (derived from this via WyomingTCPServer.updateStatus)
    // is the single source of truth for "is the satellite running" app-wide - see VAViewModel.State.satelliteRunning.
    var state: SatelliteState = SatelliteState.STOPPED
        set(value) {
            field = value
            _satelliteState.value = value
        }

    suspend fun start() {
        // Add config change listeners
        Timber.d("Satellite starting...")
        val startTime = System.currentTimeMillis()

        state = SatelliteState.STARTING
        config.eventBroadcaster.addListener(this)

        val loadedSettings = waitForSettings()
        if (!loadedSettings) {
            // Try 1 more time in case of timing issue
            sendSatelliteMessage(clientId, WyomingEvent.CUSTOM_EVENT, buildJsonObject {
                put(EVENT_TYPE, WyomingCustomEventType.SETTINGS)
            })
            if (!waitForSettings(2000)) {
                state = SatelliteState.ERROR
                return
            }
        }

        // Verify app version
        if (!validateAppVersion()) {
            BroadcastSender.sendBroadcast(context, BroadcastSender.VERSION_MISMATCH)
            state = SatelliteState.STOPPED
            return
        }

        scope.launch {
            startSensorMonitor()
            eventHandler.run()
        }

        sendDeviceStates()

        Timber.d("Satellite started in ${System.currentTimeMillis() - startTime}ms")

        state = SatelliteState.RUNNING
        BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STARTED)
    }


    suspend fun waitForSettings(waitTime: Long = 10000): Boolean {
        try {
            withTimeout(waitTime.milliseconds) {
                // Wait for settings to be processed
                while (!config.initSettings) {
                    delay(10.milliseconds)
                }
                Timber.d("Initial settings downloaded")
            }
            return true
        } catch (e: Exception) {
            Timber.e("Error waiting for settings: ${e.message.toString()}")
            return false
        }
    }

    fun sendDeviceStates() {
        // TODO: Do not put screen state in here as conflicts with update in MainActivity on satellite start
        sendStatus(
            buildJsonObject {
                putJsonObject("sensors") {
                    put("do_not_disturb", isDoNotDisturbEnabled(context))
                    put("motion_detected", false)
                }
            }
        )
    }

    fun prepareSatelliteTakeover(clientId: String) {
        // This must run before WyomingTCPServer resumes reading the new connection.
        // Otherwise its settings event can arrive before initSettings is reset.
        this.clientId = clientId
        config.initSettings = false
    }

    suspend fun handleSatelliteTakeover(clientId: String) {
        if (this.clientId != clientId) return

        val loadedSettings = waitForSettings(5000)
        if (!loadedSettings) {
            if (this.clientId != clientId) return

            // Explicitly request settings from the reconnected client
            sendSatelliteMessage(clientId, WyomingEvent.CUSTOM_EVENT, buildJsonObject {
                put(EVENT_TYPE, WyomingCustomEventType.SETTINGS)
            })
            if (!waitForSettings(2000)) {
                if (this.clientId != clientId) return

                // Connection is live; continue with cached settings rather than
                // wedging the satellite into ERROR just because the refresh was slow.
                Timber.w("Settings not received after reconnect; continuing with cached settings")
            }
        }

        if (this.clientId != clientId) return

        // Verify app version
        if (!validateAppVersion()) {
            stop()
            start()
        } else {
            BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_CLIENT_UPDATED)
        }
    }

    suspend fun stop() {
        state = SatelliteState.STOPPING
        config.eventBroadcaster.removeListener(this)

        scope.launch {
            eventHandler.stop()
            motionTask.stopCamera()
            motionTask.release()
        }.join()

        stopSensors()
        state = SatelliteState.STOPPED
        BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STOPPED)
    }

    fun validateAppVersion(): Boolean {
        // Verify app version
        if (deviceInfo.software.appVersion.toVersion() < config.minRequiredApkVersion.toVersion() ) {
            Timber.w("Does not meet min app version requirement. Version: ${deviceInfo.software.appVersion}, Min: ${config.minRequiredApkVersion} ")
            return false
        }
        return true
    }

    suspend fun processMessage(packet: WyomingPacket) {
        when (packet.type) {
            WyomingEvent.CUSTOM_EVENT -> customEventHandler(clientId, packet)
            // Voice/audio events (audio-start, transcribe, ...) are handled by the device's
            // separate voice service, not this display app.
            else -> Timber.v("Ignoring Wyoming event: ${packet.type}")
        }
    }

    // *************************************************************************
    // Custom Events
    // *************************************************************************
    private suspend fun customEventHandler(clientId: String, packet: WyomingPacket) {
        val eventType = packet.getProp("event_type")

        when (eventType) {
            WyomingCustomEventType.ACTION -> handleAction(packet.getProp("action"), packet)
            WyomingCustomEventType.SETTINGS -> handleSettings(packet.getProp("settings"))
            WyomingCustomEventType.CAPABILITIES -> handleCapabilities(clientId)
        }
    }

    private fun handleAction(action: String, packet: WyomingPacket) {
        Timber.d("Action received: $action")
        runCatching {
            val payloadStr = packet.getProp("payload")
            when (action) {
                "toast-message" -> if (payloadStr.isNotEmpty()) {
                    val msg = Json.parseToJsonElement(payloadStr).jsonObject["message"]?.jsonPrimitive?.content ?: ""
                    BroadcastSender.sendBroadcast(context, BroadcastSender.TOAST_MESSAGE, msg)
                }
                "refresh" -> config.eventBroadcaster.notifyEvent(Event("refresh", "", ""))
                // Media, wake and alarm actions belong to the device's voice/media service.
                else -> Timber.d("Ignoring action not supported by display-only VACA: $action")
            }
        }.onFailure { Timber.e("Failed to handle custom action $action: $it") }
    }

    private fun handleSettings(settings: String) {
        config.processSettings(settings)
        config.initSettings = true
    }

    fun sendCapabilities() {
        handleCapabilities(clientId)
    }

    private fun handleCapabilities(clientId: String) {
        val capabilitiesBuilder = WyomingCapabilitiesBuilder(deviceManager)
        sendSatelliteMessage(clientId,"capabilities", capabilitiesBuilder.buildInfo())
    }

    // *************************************************************************
    // Send messages
    // *************************************************************************
    fun sendStatus(data: JsonObject) {
        sendCustomEvent(WyomingCustomEventType.STATUS, data)
    }

    fun sendSetting(name: String, value: Any) {
        sendCustomEvent(WyomingCustomEventType.SETTINGS, buildJsonObject {
            put("timestamp", isoNow())
            putJsonObject("settings") {
                when (value) {
                    is String -> put(name, value)
                    is Boolean -> put(name, value)
                    is Number -> put(name, value)
                    is JsonElement -> put(name, value)
                }
            }
        })
    }

    fun sendEvent(type: String, data: JsonObject, payload: ByteArray = ByteArray(0)) {
        sendSatelliteMessage(clientId, type, data, payload)
    }

    fun sendCustomEvent(type: String, data: JsonObject) {
        sendSatelliteMessage(clientId, WyomingEvent.CUSTOM_EVENT, buildJsonObject {
            put(EVENT_TYPE, type)
            put("data", data)
        })
    }

    suspend fun startSensorMonitor() {
        sensorJob = scope.launch {
            deviceManager.sensors.collect { data ->
                val data = buildJsonObject {
                    put("timestamp", Date().toString())
                    putJsonObject("sensors") {
                        // TODO: Use a formalized sensor mapping schema instead of manual type checking and casting.
                        data.forEach { (key, value) ->
                            when (value) {
                                is Boolean -> put(key, value)
                                is Number -> put(key, value.toFloat())
                                else -> {
                                    if (Helpers.isNumber(value.toString())) {
                                        put(key, value.toString().toFloat())
                                    } else {
                                        put(key, value.toString())
                                    }
                                }
                            }
                        }
                    }
                }
                sendStatus(data)
            }
        }
        scope.launch {
            deviceManager.sensorsManager.requestSensorUpdates()
        }

        // Start motion sensor
        if (config.motionDetectionMode != "none") {
            delay(2.seconds)  // Add delay to camera start to let app start up
            motionTask.startCamera()
        }
    }

    suspend fun stopSensors() {
        sensorJob?.cancel()
        motionTask.stopCamera()
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            "requestSettings" -> {
                scope.launch {
                    if (clientId != "") {
                        sendSatelliteMessage(clientId, WyomingEvent.CUSTOM_EVENT, buildJsonObject {
                            put(EVENT_TYPE, WyomingCustomEventType.SETTINGS)
                        })
                    }
                }
            }
        }
    }

    companion object {
        fun isoNow(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    }
}
