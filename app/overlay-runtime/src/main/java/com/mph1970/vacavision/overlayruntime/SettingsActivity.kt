package com.mph1970.vacavision.overlayruntime

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.text.InputType
import android.widget.*
import com.mph1970.vacavision.overlayruntime.ble.BleConnectActivity
import com.mph1970.vacavision.overlayruntime.settings.APPConfig
import com.mph1970.vacavision.overlayruntime.web.TrustedUrl
import com.mph1970.vacavision.overlayruntime.picker.DevicePickerStore
import com.mph1970.vacavision.overlayruntime.picker.HaPickerDiscovery
import java.net.URI

/** Optional controls reached from the picker. Values take effect when returning to TV. */
class SettingsActivity : RemoteActivity() {
    private lateinit var config: APPConfig
    private lateinit var form: LinearLayout
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private lateinit var playerName: String
    private lateinit var origin: String
    private lateinit var path: String
    private var clock = true
    private var discovery: HaPickerDiscovery? = null
    private lateinit var pickerStatus: TextView
    private val update = object : Runnable {
        override fun run() {
            status.text = OverlayService.connectionStatus
            main.postDelayed(this, 2000)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        config = APPConfig.getInstance(this)
        playerName = state?.getString("name") ?: config.deviceName
        origin = state?.getString("origin") ?: config.haUrl
        path = state?.getString("path") ?: config.dashboard
        clock = state?.getBoolean("clock") ?: config.showClock
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = (resources.displayMetrics.widthPixels * .12f).toInt()
            setPadding(side, 24, side, 32)
            setBackgroundColor(Color.rgb(17, 28, 37))
        }
        setContentView(ScrollView(this).apply { addView(form) })
        text("VACA_Vision — Overlay Display Only", 28f)
        text(com.mph1970.vacavision.core.VacaProtocol.CREDITS, 14f)
        text("View Assist display device", 18f)
        text("va-${config.uuid}", 22f).typeface = android.graphics.Typeface.MONOSPACE
        text("Choose this ID in View Assist's Display Device list.", 16f)
        status = text("", 16f)
        button("Apply and return") { applyAndReturn() }.requestFocusFromTouch()
        button("Populate picker") { populatePicker(it) }
        button("Edit picker menus") {
            save(notifyService = false)
            startActivity(Intent(this, PickerMenusActivity::class.java))
        }
        button("Edit picker presets") {
            save(notifyService = false)
            startActivity(Intent(this, PickerMenusActivity::class.java).putExtra("presets", true))
        }
        pickerStatus = text("", 16f)
        button("Picker profiles — save / load") {
            save(notifyService = false)
            startActivity(Intent(this, PickerProfilesActivity::class.java))
        }
        button("Clock bubble: ${if (clock) "On" else "Off"}") { button ->
            clock = !clock; button.text = "Clock bubble: ${if (clock) "On" else "Off"}"
        }
        button("Device name: $playerName") { button -> edit("Device name", playerName) { value ->
            if (value.isBlank() || value.length > 40) "Use 1–40 characters" else {
                playerName = value; button.text = "Device name: $value"; null
            }
        } }
        button("Initial dashboard: $path") { button -> edit("Initial dashboard path", path) { value ->
            if (!TrustedUrl.isDashboardPath(value)) "Use a path such as /view-assist/clock" else {
                path = value; button.text = "Initial dashboard: $value"; null
            }
        } }
        button("Home Assistant: $origin") { button -> edit("Home Assistant address", origin) { value ->
            val url = (if ("://" in value) value else "http://$value").trimEnd('/')
            if (!TrustedUrl.validOrigin(url)) "Enter an HTTP(S) server address without a path" else {
                origin = url; button.text = "Home Assistant: $url"; null
            }
        } }
        button("Sign in to Home Assistant") {
            save(); startActivity(Intent(this, DashboardLoginActivity::class.java))
        }
        button("BLE remote") { requestBle() }
        button("Home button listener") {
            AlertDialog.Builder(this).setTitle("Dismiss overlays with Home")
                .setMessage("Fire TV blocks the normal Home notification. Enable VACA_Vision Home button in Android accessibility settings to use the optional listener. It observes Home, passes all keys through, and does not read screen content. Some Fire TV versions require ADB for this one-time setup.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open accessibility settings") { _, _ ->
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        .onFailure { Toast.makeText(this, "This Fire TV requires ADB to enable the Home listener. See the Home button guide.", Toast.LENGTH_LONG).show() }
                }.show()
        }
        text("VACA_Vision ${BuildConfig.VERSION_NAME}", 14f)
        text("Integration: VACA_Vision", 14f)
        text("Connected integration version: ${config.prefs.getString("integration_version", "Not connected yet")}", 14f)
        button("Stop VACA_Vision") {
            config.enabled = false
            if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.STOP))
            finish(); moveTaskToBack(true)
        }
    }
    private fun save(notifyService: Boolean = true) {
        if (origin != config.haUrl) {
            config.accessToken = ""; config.refreshToken = ""
            config.prefs.edit().remove("auth_client_id").apply()
        }
        config.haUrl = origin; config.deviceName = playerName; config.dashboard = path
        config.showClock = clock
        if (notifyService && OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.APPLY_SETTINGS))
    }
    private fun populatePicker(button: Button) {
        if (discovery != null) return
        save(notifyService = false)
        val source = config.haUrl
        val device = config.uuid
        val access = config.accessToken
        val refresh = config.refreshToken
        val clientId = config.prefs.getString("auth_client_id", null) ?: "$source/"
        if (access.isBlank() && refresh.isBlank()) {
            pickerStatus.text = "Sign in to Home Assistant, then populate again."
            startActivity(Intent(this, DashboardLoginActivity::class.java))
            return
        }
        val request = HaPickerDiscovery()
        discovery = request
        button.text = "Populating picker…"
        pickerStatus.text = "Reading /view-assist…"
        kotlin.concurrent.thread(name = "Picker-discovery") {
            val result = runCatching { request.fetch(source, access, refresh, clientId) }
            request.close()
            main.post {
                if (isDestroyed || isFinishing || discovery !== request) return@post
                discovery = null
                button.text = "Populate picker"
                if (source != config.haUrl || device != config.uuid) {
                    pickerStatus.text = "Home Assistant changed. Populate again."
                    return@post
                }
                result.fold(onSuccess = { views ->
                    try {
                        val update = DevicePickerStore(this, source, device).refresh(views)
                        if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.PICKER_CHANGED))
                        pickerStatus.text = "${update.total} views · ${update.added} added · ${update.removed} removed. Picker updated."
                    } catch (_: Exception) {
                        pickerStatus.text = "Could not save the picker. Previous settings retained."
                    }
                }, onFailure = { error ->
                    pickerStatus.text = when (error) {
                        is IllegalStateException, is IllegalArgumentException -> error.message
                        is java.io.IOException -> "Cannot reach Home Assistant. Check the connection and sign-in. Picker unchanged."
                        else -> "Could not read dashboard views. Picker unchanged."
                    }
                })
            }
        }
    }
    private fun applyAndReturn() {
        save()
        if (!intent.getBooleanExtra(STARTUP, false)) {
            if (config.needsSignIn) startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.BOOT, true))
            else {
                if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.PICKER))
                moveTaskToBack(true)
            }
        }
        finish()
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { applyAndReturn() }
    private fun edit(title: String, current: String, accept: (String) -> String?) {
        val input = EditText(this).apply { setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT; setText(current); selectAll() }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        remoteDialog = dialog
        dialog.setOnDismissListener { if (remoteDialog === dialog) remoteDialog = null }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val error = accept(input.text.toString().trim())
            if (error == null) dialog.dismiss() else input.error = error
        } }
        dialog.show()
    }
    private fun requestBle() {
        val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(needed, 1)
        else startActivity(Intent(this, BleConnectActivity::class.java))
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startActivity(Intent(this, BleConnectActivity::class.java))
    }
    private fun text(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0, 6, 0, 6); form.addView(this) }
    private fun styleButton(button: Button) {
        val density = resources.displayMetrics.density
        fun surface(fill: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill); cornerRadius = 8 * density; setStroke((2 * density).toInt(), stroke)
        }
        button.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), surface(0xff17434a.toInt(), 0xff59d4c7.toInt()))
            addState(intArrayOf(android.R.attr.state_pressed), surface(0xff17434a.toInt(), 0xff59d4c7.toInt()))
            addState(intArrayOf(), surface(0xff26353e.toInt(), 0xff334852.toInt()))
        }
        button.textSize = 20f; button.setTextColor(Color.WHITE)
        button.minHeight = (52 * density).toInt()
        button.setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
    }
    private fun button(label: String, action: (Button) -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; styleButton(this); setOnClickListener { action(this) }
        form.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 4, 0, 4) })
    }
    override fun onResume() { super.onResume(); main.post(update) }
    override fun onPause() { main.removeCallbacks(update); super.onPause() }
    override fun onDestroy() {
        discovery?.close()
        discovery = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("name", playerName); out.putString("origin", origin); out.putString("path", path)
        out.putBoolean("clock", clock)
        super.onSaveInstanceState(out)
    }
    companion object { const val STARTUP = "startup_settings" }
}
