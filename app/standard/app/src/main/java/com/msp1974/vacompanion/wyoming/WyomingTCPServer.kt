package com.msp1974.vacompanion.wyoming

import android.content.Context
import android.os.Build
import com.mph1970.vacavision.core.*
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.WyomingServerStatus
import com.msp1974.vacompanion.satellite.Satellite
import com.msp1974.vacompanion.utils.Event
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.json.JSONObject

interface IEvents {
    fun onEvent(event: String, data: JsonObject)
    fun onState(state: ServerState, restartIfStopped: Boolean = true)
}

/** API-compatible display adapter retaining upstream onboarding and sensor lifecycle. */
abstract class WyomingTCPServer(private val context: Context, val deviceManager: DeviceManager) : IEvents {
    val config = deviceManager.config
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<suspend () -> Unit>(64)
    private var server: VacaServer? = null
    private var advertisement: ServiceAdvertisement? = null
    private var playback: com.mph1970.vacavision.audio.VisionPlayback? = null
    private var controls: com.msp1974.vacompanion.utils.UpstreamControls? = null
    private var crown: CrownStatusClient? = null
    private var role = VisionRole.STANDARD_DISPLAY
    private var connected = false
    private var closed = false
    var satellite: Satellite? = null
    var state = ServerState.STOPPED
        private set
    init { scope.launch { for (event in events) event() } }
    private fun update() {
        deviceManager.updateServerState(WyomingServerStatus(state, connected, connected && satellite?.state == SatelliteState.RUNNING))
    }
    suspend fun startServer() {
        if (closed || state != ServerState.STOPPED) return
        state = ServerState.STARTING; update()
        try {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0"
            server = VacaServer(
                { VacaProtocol.info("VACA_Vision ${Build.MODEL}", config.uuid, version) },
                { VacaProtocol.capabilities("${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT, version, false) },
                { settings, host -> events.trySend { applySettings(settings, host) } },
                { action, payload -> events.trySend { action(action, payload) } },
                { message -> events.trySend {
                    connected = message == "Home Assistant connected"
                    if (!connected) { playback?.disconnected(); controls?.disconnected() }
                    update()
                } },
                { config.pairedDeviceID },
            ).also { it.start() }
            advertisement = ServiceAdvertisement(context) {}.also {
                it.register("VACA_Vision-${config.uuid.takeLast(12)}", VacaProtocol.SERVICE_TYPE, VacaProtocol.PORT)
            }
            state = ServerState.RUNNING; update(); onState(state, false)
        } catch (e: Exception) {
            server?.close(); advertisement?.close()
            state = ServerState.ERRORED; update(); onState(state, false)
        }
    }
    private suspend fun applySettings(settings: JSONObject, host: String) {
        if (closed) return
        val selected = runCatching { VisionRole.resolve(settings.getString("role"), false) }.getOrElse {
            server?.publish(WireEvent("error", JSONObject().put("code", "unsupported-role"))); return
        }
        config.homeAssistantConnectedIP = host
        if (config.pairedDeviceID.isEmpty()) config.pairedDeviceID = host
        config.edaOwnsTimeout = selected == VisionRole.STANDARD_UPSTREAM
        config.processSettings(settings.toString())
        if (satellite == null) {
            satellite = object : Satellite(context, deviceManager, scope, "vision") {
                override fun onEvent(event: String, data: JsonObject) {}
                override fun sendSatelliteMessage(clientId: String, type: String, data: JsonObject, payload: ByteArray) {
                    if (!closed && connected && payload.isEmpty()) server?.publish(WireEvent(type, JSONObject(data.toString())))
                }
            }.also { it.start() }
        }
        connected = true
        role = selected
        if (role == VisionRole.STANDARD_UPSTREAM && controls == null) {
            controls = com.msp1974.vacompanion.utils.UpstreamControls(context,
                { level ->
                    config.processSettings(JSONObject().put("screen_brightness", level).toString())
                    if (android.provider.Settings.System.canWrite(context)) {
                        android.provider.Settings.System.putInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, level * 255 / 100)
                    }
                    publishDisplaySettings()
                },
                { config.screenAlwaysOn },
                { keep ->
                    config.processSettings(JSONObject().put("screen_always_on", keep).toString())
                    publishDisplaySettings()
                },
                { value -> server?.publish(VacaProtocol.custom("playback", value)) }
            ).also { it.start() }
        } else if (role != VisionRole.STANDARD_UPSTREAM) { controls?.close(); controls = null }
        if (role == VisionRole.STANDARD_UPSTREAM) controls?.start()
        VisionState.role = role
        if (role == VisionRole.STANDARD_DOT) RemoteMicrophone.update(settings.optJSONObject("microphone"))
        else RemoteMicrophone.reset()
        if (role.crown && crown == null) {
            crown = CrownStatusClient { value ->
                if (!closed) {
                    VisionState.crown = value.toString()
                    server?.publish(VacaProtocol.custom("status", JSONObject().put("crown", value)))
                }
            }.also { it.start() }
        } else if (!role.crown) {
            crown?.close(); crown = null
            VisionState.crown = CrownStatusClient.offline().toString()
        }
        if (role == VisionRole.STANDARD_DOT && playback == null) {
            playback = com.mph1970.vacavision.audio.VisionPlayback(context, config.uuid, "VACA_Vision ${Build.MODEL}") { value ->
                server?.publish(VacaProtocol.custom("playback", value))
            }
        } else if (role != VisionRole.STANDARD_DOT) { playback?.close(); playback = null }
        playback?.publishState()
        publishDisplaySettings()
        satellite?.sendDeviceStates(); update()
    }
    private suspend fun action(action: String, payload: JSONObject) {
        if (closed) return
        if (role == VisionRole.STANDARD_UPSTREAM && action in setOf("voice-begin", "voice-keepalive", "voice-end", "voice-state")) {
            controls?.command(action, payload)
            return
        }
        if (action in setOf("voice-begin", "voice-keepalive", "voice-end", "voice-play", "reply-volume", "music-command")) {
            playback?.command(action, payload)
            return
        }
        when (action) {
            "microphone-state" -> if (role == VisionRole.STANDARD_DOT) RemoteMicrophone.update(payload)
            "navigate", "show-dashboard" -> {
                val path = payload.optString("path", payload.optString("url"))
                if (path.startsWith("/") && !path.startsWith("//") && !path.contains('\\') && path.none { it.code < 32 }) {
                    config.homeAssistantDashboard = path
                    config.eventBroadcaster.notifyEvent(Event("visionNavigate", "", path))
                }
            }
            "show-settings" -> config.eventBroadcaster.notifyEvent(Event("visionSettings", "", ""))
            "set-display" -> {
                // Explicit allowlist: remote settings cannot change endpoints, identity or audio ownership.
                val allowed = setOf("screen_on", "screen_brightness", "screen_always_on", "screen_auto_brightness", "screen_saver", "dark_mode", "screen_timeout", "screen_orientation_mode", "diagnostics_enabled", "quick_actions", "motion_detection_mode", "motion_detection_sensitivity", "screen_on_motion")
                val clean = JSONObject()
                payload.keys().forEach { if (it in allowed) clean.put(it, payload.get(it)) }
                config.processSettings(clean.toString())
                controls?.refresh()
                publishDisplaySettings()
            }
            else -> satellite?.processMessage(WyomingPacket("custom-event", buildJsonObject {
                put("event_type", "action"); put("action", action)
                put("payload", Json.parseToJsonElement(payload.toString()))
            }))
        }
    }
    private fun publishDisplaySettings() {
        val values = JSONObject().put("role", role.id)
            .put("screen_on", config.screenOn)
            .put("screen_always_on", config.screenAlwaysOn)
            .put("screen_auto_brightness", config.screenAutoBrightness)
            .put("screen_brightness", (config.screenBrightness * 100).toInt())
            .put("screen_saver", config.screenSaver)
            .put("dark_mode", config.darkMode)
            .put("diagnostics_enabled", config.diagnosticsEnabled)
            .put("quick_actions", config.enableQuickActions)
            .put("motion_detection_mode", config.motionDetectionMode)
            .put("motion_detection_sensitivity", config.motionDetectionSensitivity)
            .put("screen_on_motion", config.screenOnMotion)
        server?.publish(VacaProtocol.custom("settings", JSONObject().put("settings", values)))
    }
    fun stopServer() {
        if (closed) return
        closed = true; connected = false
        playback?.close(); playback = null
        controls?.close(); controls = null
        RemoteMicrophone.reset()
        crown?.close(); crown = null; VisionState.crown = CrownStatusClient.offline().toString()
        server?.close(); advertisement?.close(); events.close()
        scope.launch {
            satellite?.stop(); satellite = null
            state = ServerState.STOPPED; update(); onState(state, false)
            scope.cancel()
        }
    }
}
