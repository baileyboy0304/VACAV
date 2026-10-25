  package com.msp1974.vacompanion.ui

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.core.content.ContextCompat.getString
import java.io.Closeable
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.device.sensors.NetworkStatus
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.Permissions
import com.msp1974.vacompanion.utils.Network
import com.msp1974.vacompanion.utils.Updater
import com.msp1974.vacompanion.device.DeviceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import androidx.core.net.toUri
import com.msp1974.vacompanion.device.MotionDetectionEngine.Companion.MOTION_INTERVAL_TIMEOUT
import com.msp1974.vacompanion.device.sensors.SensorState
import com.msp1974.vacompanion.utils.WebViewGestureDetector

  class VADialog(
    val title: String = "AlertDialog",
    val message: String = "Message",
    val confirmText: String = "Yes",
    val dismissText: String = "No",
    val confirmCallback: () -> Unit,
    val dismissCallback: () -> Unit
) {
    fun onConfirm() {
        confirmCallback()
    }

    fun onDismiss() {
        dismissCallback()
    }
}

data class UpdateStatus(
    var updateAvailable: Boolean = false,
    var availableVersion: String = "0.0.0"
)

data class PermissionsStatus(
    var hasCorePermissions: Boolean = false,
    var hasOptionalPermissions: Boolean = false,
    var recordAudio: Boolean = false,
    var camera: Boolean = false,
    var postNotifications: Boolean = false,
    var writeExternalStorage: Boolean = false,
    var writeSettings: Boolean = false,
    var notificationPolicy: Boolean = false,
    var deviceAdmin: Boolean = false
)

data class DiagnosticInfo(
    var show: Boolean = false,
    var motionDetected: Boolean = false,
    var hasCamera: Boolean = false,
    var lastMotionTimestamp: Long = 0,
    var motionInterval: Int = 10000,
    var motionDetectionMode: String = "motion",
)

data class State(
    val statusMessage: String = "Initialising...",
    var orientation: Int = Configuration.ORIENTATION_LANDSCAPE,

    var launchOnBoot: Boolean = true,
    // Single source of truth for "is the satellite running" on the UI side - mirrors
    // WyomingServerStatus.satelliteRunning (itself derived from Satellite.state) and is
    // what MainActivity's Compose tree keys off to decide whether to show WebViewScreen.
    var satelliteRunning: Boolean = false,
    var darkMode: Boolean = false,
    var isDND: Boolean = false,
    var screenBlank: Boolean = true,

    var appInfo: Map<String, String> = mapOf(),
    var diagnosticInfo: DiagnosticInfo = DiagnosticInfo(),

    var showAlertDialog: Boolean = false,
    var alertDialog: VADialog? = null,
    var showMenu: Boolean = false,
    var menuOpenedByAction: Boolean = false,
    var permissions: PermissionsStatus = PermissionsStatus(),
    var updates: UpdateStatus = UpdateStatus(),
    var webViewPageLoadingStage: PageLoadingStage = PageLoadingStage.NOT_STARTED,
    var showUUIDChangeDialog: Boolean = false,
    var isNetworkConnected: Boolean = true,
    var showSettings: Boolean = false,
    var cameraStreamActive: Boolean = false,
    var motionDetectionSensitivity: Int = 0,
    var motionDetectionMode: String = "motion",
    var sensorState: SensorState = SensorState()
    )

@HiltViewModel
class VAViewModel @Inject constructor(
    application: Application,
    val deviceManager: DeviceManager,
): ViewModelBase(application), EventListener, Closeable {

    val config: APPConfig = deviceManager.config
    val deviceInfo = deviceManager.deviceInfo
    private val _vacaState = MutableStateFlow(State())
    val vacaState: StateFlow<State> = _vacaState.asStateFlow()

    var resources: Resources = application.resources
    var permissions: Permissions = Permissions(application.applicationContext, deviceManager)
    val network = Network(application.applicationContext)
    val updater = Updater(application.applicationContext)

    init {
        _vacaState.value = State()

        //TODO: put under device manager
        network.setWifiLock()

        config.eventBroadcaster.addListener(this)
        initValues()

        viewModelScope.launch {
            deviceManager.status.collect { status ->
                val oldNetworkState = _vacaState.value.isNetworkConnected
                val wasRunning = _vacaState.value.satelliteRunning
                val isRunning = status.wyoming.satelliteRunning

                _vacaState.update { currentState ->
                    currentState.copy(
                        isNetworkConnected = status.network.status == NetworkStatus.Available,
                        satelliteRunning = isRunning,
                        isDND = status.isDND,
                        darkMode = status.darkMode,
                        webViewPageLoadingStage = status.webViewPageLoadingStage,
                        cameraStreamActive = status.cameraStreamActive,
                        screenBlank = status.screenBlank,
                        showMenu = if (wasRunning && !isRunning) false else currentState.showMenu,
                        menuOpenedByAction = if (wasRunning && !isRunning) false else currentState.menuOpenedByAction,
                        showSettings = if (wasRunning && !isRunning) false else currentState.showSettings,
                        sensorState = status.sensors
                    )
                }

                if (wasRunning && !isRunning) {
                    config.settingsOpen = false
                }

                if (oldNetworkState != _vacaState.value.isNetworkConnected) {
                    onNetworkStateChange(status.network.status)
                }
            }
        }
    }

    fun initValues() {
        _vacaState.update { currentState ->
            currentState.copy(
                launchOnBoot = config.startOnBoot,
                motionDetectionSensitivity = config.motionDetectionSensitivity,
                motionDetectionMode = config.motionDetectionMode,
                // TODO: Move this into a dedicated configuration observer pattern to handle live updates.
                diagnosticInfo = currentState.diagnosticInfo.copy(
                    show = config.diagnosticsEnabled,
                    hasCamera = deviceInfo.hardware.hasFrontCamera,
                    motionDetectionMode = config.motionDetectionMode
                )
            )
        }

        buildAppInfo()
    }

    override fun close() {
        network.releaseWifiLock()
    }

    var launchOnBoot: Boolean
        get() = config.startOnBoot
        set(value) {
            _vacaState.update { currentState ->
                currentState.copy(
                    launchOnBoot = value
                )
            }
            config.startOnBoot = value
        }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        when (event.eventName) {
            "pairedDeviceID" -> buildAppInfo()
            "openSettings" -> onOpenSettingsAction()
            "darkMode" -> {
                deviceManager.updateDarkModeStatus(event.newValue as Boolean)
            }
            "doNotDisturb" -> {
                deviceManager.updateDNDStatus(event.newValue as Boolean)
            }
            "diagnosticsEnabled" -> {
                _vacaState.update { currentState ->
                    currentState.copy(
                        diagnosticInfo = _vacaState.value.diagnosticInfo.copy(
                            show = event.newValue as Boolean
                        )
                    )
                }
            }
            "motionDetectionSensitivity" -> {
                val sensitivity = event.newValue as Int
                _vacaState.update { currentState ->
                    currentState.copy(
                        motionDetectionSensitivity = sensitivity
                    )
                }
            }
            "motionDetectionMode" -> {
                val mode = event.newValue as String
                _vacaState.update { currentState ->
                    currentState.copy(
                        motionDetectionMode = mode,
                        diagnosticInfo = currentState.diagnosticInfo.copy(
                            motionDetectionMode = mode
                        )
                    )
                }
            }
            "motion" -> {
                val value = event.newValue as? Boolean ?: true
                if (value) {
                    val now = System.currentTimeMillis()
                    _vacaState.update { currentState ->
                        currentState.copy(
                            diagnosticInfo = currentState.diagnosticInfo.copy(
                                motionDetected = true,
                                lastMotionTimestamp = now,
                                motionInterval = MOTION_INTERVAL_TIMEOUT
                            )
                        )
                    }
                } else {
                    _vacaState.update { currentState ->
                        currentState.copy(
                            diagnosticInfo = currentState.diagnosticInfo.copy(
                                motionDetected = false
                            )
                        )
                    }
                }
            }
            else -> consumed = false
        }
        if (consumed) {
            Timber.d("ViewModel - Event: ${event.eventName} - ${event.newValue}")
        }
    }

    fun showUpdateDialog(alertDialog: VADialog) {
        val alert = VADialog(
            title = alertDialog.title,
            message = alertDialog.message,
            confirmText = alertDialog.confirmText,
            dismissText = alertDialog.dismissText,
            confirmCallback = {
                _vacaState.update { currentState ->
                    currentState.copy(
                        alertDialog = null
                    )
                }
                alertDialog.confirmCallback()
            },
            dismissCallback = {
                _vacaState.update { currentState ->
                    currentState.copy(
                        alertDialog = null
                    )
                }
                alertDialog.dismissCallback()
            },
        )

        _vacaState.update { currentState ->
            currentState.copy(
                alertDialog = alert,
            )
        }
    }

    fun onShowDiagnostics(show: Boolean) {
        config.diagnosticsEnabled = show
    }



    fun onToggleDND(enabled: Boolean) {
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.isNotificationPolicyAccessGranted) {
            val filter = if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            notificationManager.setInterruptionFilter(filter)
            config.doNotDisturb = enabled
            deviceManager.updateDNDStatus(enabled)
        } else {
            requestNotificationPolicyAccess()
        }
    }

    fun onOpenSettingsAction() {
        _vacaState.update { currentState ->
            currentState.copy(
                showMenu = true,
                menuOpenedByAction = true
            )
        }
        config.settingsOpen = true
    }

    fun setStatusMessage(statusMessage: String) {
        _vacaState.update { currentState ->
            currentState.copy(
                statusMessage = statusMessage
            )
        }
    }

    fun setScreenBlank(screenOn: Boolean) {
        deviceManager.updateScreenBlankStatus(screenOn)
    }

    fun setWebViewPageLoadingState(stage: PageLoadingStage) {
        Timber.d("WebView page loading state: $stage")
        deviceManager.updateWebViewPageLoadingStage(stage)
    }

    fun onNetworkStateChange(status: NetworkStatus) {
        when (status) {
            NetworkStatus.Unavailable  -> setStatusMessage(application.getString(R.string.status_waiting_for_network))
            NetworkStatus.Available -> setStatusMessage(getString(application.applicationContext, R.string.status_waiting_for_connection))
        }
        buildAppInfo()
    }

    fun onGesture(gestureEvent: WebViewGestureDetector.GestureEvent) {
        config.eventBroadcaster.notifyEvent(Event("gesture", "", gestureEvent))
    }

    fun hideSystemUI() {
        config.eventBroadcaster.notifyEvent(Event("hideSystemUI", "", ""))
    }

    fun onEvent(event: Event) {
        config.eventBroadcaster.notifyEvent(event)
    }

    private fun buildAppInfo() {
       _vacaState.update { currentState ->
            currentState.copy(
                appInfo = mapOf(
                    "Version" to deviceInfo.software.appVersion,
                    "IP Address" to (if (Helpers.isNetworkAvailable(config.context)) Helpers.getIpv4HostAddress() else ""),
                    "Port" to APPConfig.SERVER_PORT.toString(),
                    "Device ID" to config.uuid,
                    "Paired to" to config.pairedDeviceID,
                )
           )
       }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            try {
                val latest = updater.getLatestRelease(forceUpdate = true)
                val current = deviceInfo.software.appVersion

                if (updater.isUpdateAvailable()) {
                    showUpdateDialog(
                        VADialog(
                            title = "Update Available",
                            message = "A new version of View Assist Companion is available (v${latest.version}). Would you like to update from v$current?",
                            confirmText = "Update",
                            dismissText = "Cancel",
                            confirmCallback = {
                                BroadcastSender.sendBroadcast(config.context, BroadcastSender.RUN_UPDATE)
                            },
                            dismissCallback = {}
                        )
                    )
                } else {
                    showUpdateDialog(
                        VADialog(
                            title = "Up to Date",
                            message = "You are already using the latest version of View Assist Companion (v$current).",
                            confirmText = "OK",
                            dismissText = "",
                            confirmCallback = {},
                            dismissCallback = {}
                        )
                    )
                }
            } catch (e: Exception) {
                Timber.e("Error checking for update: ${e.message}")
                showUpdateDialog(
                    VADialog(
                        title = "Update Check Failed",
                        message = "An error occurred while checking for updates: ${e.message}",
                        confirmText = "OK",
                        dismissText = "",
                        confirmCallback = {},
                        dismissCallback = {}
                    )
                )
            }
        }
    }

    fun requestPermissions() {
        BroadcastSender.sendBroadcast(config.context, BroadcastSender.OPEN_PERMISSION_SCREEN)
    }

    fun refreshPermissionsStatus() {
        _vacaState.update { currentState ->
            currentState.copy(
                permissions = PermissionsStatus(
                    hasCorePermissions = permissions.hasCorePermissions(),
                    hasOptionalPermissions = permissions.hasOptionalPermissions(),
                    recordAudio = permissions.hasPermission(android.Manifest.permission.RECORD_AUDIO),
                    camera = permissions.hasPermission(android.Manifest.permission.CAMERA),
                    postNotifications = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        permissions.hasPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else true,
                    writeExternalStorage = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                        permissions.hasPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else true,
                    writeSettings = permissions.hasWriteSettingsPermission(),
                    notificationPolicy = permissions.hasNotificationAccessPolicyPermission(),
                    deviceAdmin = permissions.isDeviceAdmin()
                )
            )
        }
    }

    fun togglePermission(permission: String) {
        if (this.permissions.hasPermission(permission)) {
            openAppSettings()
        } else {
            // For runtime permissions, we trigger the system request
            BroadcastSender.sendBroadcast(config.context, BroadcastSender.OPEN_PERMISSION_SCREEN, permission)
        }
    }

    fun openAppSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${config.context.packageName}".toUri()
        ).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        config.context.startActivity(intent)
    }

    fun requestWriteSettingsPermission() {
        BroadcastSender.sendBroadcast(config.context, BroadcastSender.OPEN_PERMISSION_SCREEN, "WRITE_SETTINGS")
    }

    fun requestNotificationPolicyAccess() {
        BroadcastSender.sendBroadcast(config.context, BroadcastSender.OPEN_PERMISSION_SCREEN, "NOTIFICATION_POLICY")
    }

    fun requestDeviceAdmin() {
        BroadcastSender.sendBroadcast(config.context, BroadcastSender.OPEN_PERMISSION_SCREEN, "DEVICE_ADMIN")
    }

    fun setPermissionsStatus(core: Boolean, optional: Boolean) {
        refreshPermissionsStatus()
    }

    fun clearPairedDevice() {
        config.pairedDeviceID = ""
        config.accessToken = ""
        config.refreshToken = ""
        config.tokenExpiry = 0
        config.alwaysIgnoreSSLErrors = false
    }

    fun setUUID(uuid: String = "") {
        // TODO: Add validation
        if (uuid != "" && uuid != config.uuid) {
            config.uuid = uuid
            clearPairedDevice()
            buildAppInfo()
            config.eventBroadcaster.notifyEvent(Event("restartZeroconf", "", ""))
        }
    }

    fun setShowMenu(show: Boolean) {
        _vacaState.update { currentState ->
            currentState.copy(
                showMenu = show,
                menuOpenedByAction = if (!show) false else currentState.menuOpenedByAction
            )
        }
        config.settingsOpen = show
    }

    fun setShowSettings(show: Boolean) {
        _vacaState.update { currentState ->
            currentState.copy(
                showSettings = show
            )
        }
        config.settingsOpen = show
    }

    fun setCameraStreamActive(active: Boolean) {
        config.cameraStreamActive = active
        deviceManager.updateCameraStreamActive(active)
        config.eventBroadcaster.notifyEvent(Event("cameraStreamActive", "", active))
    }
}
