package com.mph1970.vacavision.overlayruntime

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.app.*
import android.content.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.mph1970.vacavision.core.ServiceAdvertisement
import com.mph1970.vacavision.overlayruntime.overlay.OverlayController
import com.mph1970.vacavision.overlayruntime.overlay.AlarmOverlay
import com.mph1970.vacavision.core.*
import com.mph1970.vacavision.overlayruntime.settings.APPConfig
import com.mph1970.vacavision.overlayruntime.picker.DevicePickerStore
import com.mph1970.vacavision.overlayruntime.picker.PickerProfileClient
import com.mph1970.vacavision.overlayruntime.web.*
import org.json.JSONObject
import java.net.URI
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var config: APPConfig
    private var advertisement: ServiceAdvertisement? = null
    private var server: VacaServer? = null
    private var muteLine: com.mph1970.vacavision.overlayruntime.overlay.MicrophoneMuteOverlay? = null
    private var dotRole = false
    private var playback: com.mph1970.vacavision.audio.VisionPlayback? = null
    private val profileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var haConnected = false
    private var pickerProfiles = false
    private var web: DashboardWebView? = null
    private var overlay: OverlayController? = null
    private var alarms: AlarmOverlay? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null
    private var closed = false
    private var connectivity: ConnectivityManager? = null
    private val networkListener = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.postDelayed({ if (!closed) web?.openDashboard() }, 1500) }
    }
    override fun onCreate() {
        super.onCreate()
        if (!com.msp1974.vacompanion.DeviceMode.isOverlay(this)) { stopSelf(); return }
        config = APPConfig.getInstance(this)
        RemoteMicrophone.reset()
        muteLine = com.mph1970.vacavision.overlayruntime.overlay.MicrophoneMuteOverlay(this)
        profileScope.launch { RemoteMicrophone.muted.collect { muted ->
            if (Settings.canDrawOverlays(this@OverlayService)) muteLine?.show(muted)
        } }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel("overlay", "TV display overlays", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.OPEN_PICKER, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(100, NotificationCompat.Builder(this, "overlay").setSmallIcon(R.drawable.ov_ic_notification)
            .setContentTitle("VACA_Vision").setContentText("Open dashboard picker").setSilent(true)
            .setContentIntent(open).setOngoing(true).addAction(0, "Stop", stop).build())
        running = true
        advertisement = ServiceAdvertisement(this, ::report)
        try {
            multicast = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createMulticastLock("VACA_Vision:discovery").apply { setReferenceCounted(false); acquire() }
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VACA_Vision:connection").apply { setReferenceCounted(false); acquire() }
            server = VacaServer(
                { VacaProtocol.info(config.deviceName, config.uuid, BuildConfig.VERSION_NAME) },
                { VacaProtocol.capabilities(Build.MANUFACTURER + " " + Build.MODEL, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME) },
                { settings, host -> main.post { applySettings(settings, host) } },
                { action, payload -> main.post { performAction(action, payload) } }, ::report,
                { runCatching { URI(config.haUrl).host.orEmpty() }.getOrDefault("") },
            ).also { it.start() }
            PickerProfileClient.send = { request ->
                if (haConnected && pickerProfiles && !closed) {
                    server?.publish(VacaProtocol.custom("picker-profile-request", request)); true
                } else false
            }
            advertisement?.register("${config.deviceName.take(30)}-${config.uuid.take(8)}", VacaProtocol.SERVICE_TYPE, VacaProtocol.PORT)
            createDashboard()
            alarms = AlarmOverlay(this) { status -> server?.publish(VacaProtocol.custom("status", status)) }
            restoreBleRemote()
            connectivity = getSystemService(ConnectivityManager::class.java)
            connectivity?.registerDefaultNetworkCallback(networkListener)
            report("VACA listening on port 10900")
        } catch (e: Exception) { report("Startup failed: ${e.message}"); stopSelf() }
    }
    private fun createDashboard() {
        overlay?.destroy(); overlay = null
        web?.destroy(); web = null
        if (!Settings.canDrawOverlays(this)) { report("Grant display-over-other-apps permission, then restart"); return }
        web = DashboardWebView(this, ::report).also { view ->
            view.onClockTimeZoneChanged = { overlay?.refreshClock() }
            overlay = OverlayController.install(this, view).also { it.configureFade(300, 700) }
            overlay?.setConnectionOnline(haConnected)
            view.onNavigate = { path ->
                overlay?.considerCurrentUrl(path)
                publishPath(overlay?.currentPath ?: path)
            }
            view.onRendererGone = { if (!closed) createDashboard() }
            view.onSignInRequired = { promptSignIn() }
            view.openDashboard()
            overlay?.onPathPresented = ::publishPath
            overlay?.enablePreparedViews()
        }
    }
    private fun publishPath(path: String) {
        server?.publish(VacaProtocol.custom("status", JSONObject().put("sensors", JSONObject().put("current_path", runCatching { URI(path).path }.getOrDefault(path)))))
    }
    private fun promptSignIn() {
        report("Home Assistant sign-in required")
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.BOOT, true)
        val open = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        getSystemService(NotificationManager::class.java).notify(101,
            NotificationCompat.Builder(this, "overlay").setSmallIcon(R.drawable.ov_ic_notification)
                .setContentTitle("Sign in to Home Assistant").setContentText("Open VACA_Vision to reconnect dashboards")
                .setContentIntent(open).setAutoCancel(true).build())
        runCatching { startActivity(intent) }
    }
    private fun restoreBleRemote() {
        if (getSharedPreferences("vaca_prefs", MODE_PRIVATE).getString("last_remote_mac", null).isNullOrBlank()) return
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) return
        runCatching { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, com.mph1970.vacavision.overlayruntime.ble.BleRemoteService::class.java).setAction(com.mph1970.vacavision.overlayruntime.ble.BleRemoteService.ACTION_START_SCAN)) }
    }
    private fun applySettings(settings: JSONObject, host: String) {
        if (closed) return
        val role = runCatching { VisionRole.resolve(settings.optString("role"), true) }.getOrElse {
            report("Unsupported Vision role"); return
        }
        dotRole = role == VisionRole.OVERLAY_ECHO_DOT
        if (dotRole) RemoteMicrophone.update(settings.optJSONObject("microphone"))
        else RemoteMicrophone.reset()
        if (role == VisionRole.OVERLAY_ECHO_DOT && playback == null) {
            playback = com.mph1970.vacavision.audio.VisionPlayback(this, config.uuid, "VACA_Vision ${Build.MODEL}") { value ->
                server?.publish(VacaProtocol.custom("playback", value))
            }
        } else if (role != VisionRole.OVERLAY_ECHO_DOT) {
            playback?.close(); playback = null
        }
        playback?.publishState()
        pickerProfiles = settings.optInt("picker_profiles") == 1
        config.prefs.edit().putString("integration_version", settings.optString("integration_version"))
            .putString("min_required_apk_version", settings.optString("min_required_apk_version")).apply()
        com.mph1970.vacavision.overlayruntime.overlay.HaClock(config).rememberTimeZone(config.haUrl, settings.optString("time_zone"))
        overlay?.refreshClock()
        // Retain an explicitly selected URL; learn it from VACA on first provisioning.
        if (config.haUrl.isBlank()) {
            val offered = settings.optString("ha_url").trimEnd('/')
            config.haUrl = if (TrustedUrl.validOrigin(offered)) offered else "http://$host:${settings.optInt("ha_port", 8123)}"
            web?.openDashboard()
        }
        val path = settings.optString("ha_dashboard", settings.optString("dashboard_url", settings.optString("url", "")))
        if (TrustedUrl.isDashboardPath(path)) overlay?.navigateToPath(path)
        overlay?.markOnlineAndSystemReady()
        // HA may connect after the page has loaded; send its current path immediately too.
        val currentPath = overlay?.currentPath ?: config.dashboard
        server?.publish(VacaProtocol.custom("status", JSONObject().put("sensors", JSONObject().put("current_path", currentPath))))
        server?.publish(VacaProtocol.custom("settings", JSONObject().put("settings", JSONObject()
            .put("overlay_enabled", config.overlayEnabled))))
        alarms?.let { server?.publish(VacaProtocol.custom("status", it.status())) }
    }
    private fun performAction(action: String, payload: JSONObject) {
        if (closed) return
        if (action in setOf("voice-begin", "voice-keepalive", "voice-end", "voice-play", "reply-volume", "music-command")) {
            playback?.command(action, payload)
            return
        }
        when (action) {
            "microphone-state" -> if (dotRole) RemoteMicrophone.update(payload)
            "picker-profile-result" -> PickerProfileClient.receive(payload)
            "picker-profile-device" -> profileDeviceAction(payload)
            "navigate", "show-dashboard" -> overlay?.navigateToPath(payload.optString("path", payload.optString("url")))
            "refresh" -> overlay?.reloadDashboards()
            "hide-overlay" -> overlay?.hide()
            "set-overlay-enabled" -> {
                config.overlayEnabled = payload.optBoolean("enabled", true)
                if (!config.overlayEnabled) overlay?.hide()
                server?.publish(VacaProtocol.custom("settings", JSONObject().put("settings", JSONObject().put("overlay_enabled", config.overlayEnabled))))
            }
            "alarm" -> alarms?.action(payload)
            "ack-alarm-dismissal" -> alarms?.acknowledge(payload.optString("id"))
            "show-picker" -> overlay?.showPicker()
            "show-ble-setup" -> {
                overlay?.hide()
                startActivity(Intent(this, com.mph1970.vacavision.overlayruntime.ble.BleConnectActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            "toast-message" -> android.widget.Toast.makeText(this, payload.optString("message", payload.optString("text")), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    private fun profileDeviceAction(payload: JSONObject) {
        val id = payload.optString("request_id")
        if (id.isBlank() || id.length > 80) return
        val origin = config.haUrl; val device = config.uuid
        val editorOpen = RemoteActivity.pickerEditorOpen
        profileScope.launch {
            val reply = JSONObject().put("request_id", id)
            try {
                val result = withContext(Dispatchers.IO) {
                    val store = DevicePickerStore(this@OverlayService, origin, device)
                    check(!editorOpen) { "Close the picker editor on the TV first." }
                    check(origin == config.haUrl && device == config.uuid) { "Device configuration changed." }
                    when (payload.optString("operation")) {
                        "get" -> JSONObject().put("profile", store.exportProfile()).put("can_restore", store.hasRestorePoint())
                        "apply" -> { store.applyProfile(payload.getJSONObject("profile")); JSONObject().put("applied", true) }
                        "restore" -> { store.restorePrevious(); JSONObject().put("restored", true) }
                        else -> error("Unknown picker operation.")
                    }
                }
                if (payload.optString("operation") != "get") overlay?.refreshPreparedViews()
                reply.put("ok", true).put("result", result)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reply.put("ok", false).put("error", e.message?.take(180) ?: "Picker operation failed.")
            }
            server?.publish(VacaProtocol.custom("picker-profile-device-result", reply))
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::config.isInitialized || !com.msp1974.vacompanion.DeviceMode.isOverlay(this)) {
            stopSelf(); return START_NOT_STICKY
        }
        when (intent?.action) {
            STOP -> { config.enabled = false; stopService(Intent(this, com.mph1970.vacavision.overlayruntime.ble.BleRemoteService::class.java)); stopSelf(); return START_NOT_STICKY }
            RELOAD -> createDashboard()
            AUTH_CHANGED -> web?.openDashboard()
            APPLY_SETTINGS -> createDashboard()
            PICKER_CHANGED -> overlay?.refreshPreparedViews()
            PICKER -> overlay?.showPicker(remote = true)
            TOGGLE_PICKER -> overlay?.togglePicker()
            PREVIEW -> overlay?.navigateToPath(intent.getStringExtra("path") ?: "/view-assist/weather")
            HIDE -> overlay?.hide()
        }
        return START_STICKY
    }
    private fun report(message: String) {
        if (message == "Home Assistant connected" || message == "Home Assistant paused" || message == "Waiting for Home Assistant" || message.startsWith("VACA connection ended")) {
            main.post {
                haConnected = message == "Home Assistant connected"
                if (!haConnected) { pickerProfiles = false; PickerProfileClient.disconnect(); playback?.disconnected() }
                if (!closed) overlay?.setConnectionOnline(haConnected)
            }
        }
        connectionStatus = message
        android.util.Log.i("VACA_Vision", message)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        report("Android memory trim level=$level")
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            overlay?.releaseUnselectedCamera()
    }
    override fun onDestroy() {
        closed = true; running = false
        PickerProfileClient.send = null; PickerProfileClient.disconnect()
        main.removeCallbacksAndMessages(null)
        runCatching { connectivity?.unregisterNetworkCallback(networkListener) }
        alarms?.close(); overlay?.destroy(); web?.destroy()
        muteLine?.close(); muteLine = null; RemoteMicrophone.reset()
        playback?.close(); playback = null
        server?.close(); advertisement?.close()
        profileScope.cancel()
        runCatching { multicast?.release() }; runCatching { wake?.release() }
        super.onDestroy()
    }
    companion object {
        const val STOP = "overlay.stop"
        const val RELOAD = "overlay.reload"
        const val AUTH_CHANGED = "overlay.auth_changed"
        const val APPLY_SETTINGS = "overlay.apply_settings"
        const val PICKER_CHANGED = "overlay.picker_changed"
        const val PREVIEW = "overlay.preview"
        const val PICKER = "overlay.picker"
        const val TOGGLE_PICKER = "overlay.toggle_picker"
        const val HIDE = "overlay.hide"
        @Volatile var running = false
        @Volatile var connectionStatus = "Stopped"
    }
}

