package com.mph1970.vacavision.overlayruntime

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.core.content.ContextCompat
import com.mph1970.vacavision.overlayruntime.settings.APPConfig

/** Transparent launcher: successful startup has no page, toast, or automatic picker. */
class MainActivity : RemoteActivity() {
    private lateinit var config: APPConfig
    private var signInAttempted = false
    private var remoteSetupAttempted = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!com.msp1974.vacompanion.DeviceMode.isOverlay(this)) { finish(); return }
        config = APPConfig.getInstance(this)
        config.enabled = true
        signInAttempted = state?.getBoolean("sign_in_attempted") ?: false
        remoteSetupAttempted = state?.getBoolean("remote_setup_attempted") ?: false
    }
    override fun onResume() { super.onResume(); advance() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        signInAttempted = false
    }
    private fun advance() {
        if (isFinishing || !::config.isInitialized) return
        if (!Settings.canDrawOverlays(this)) {
            showPrompt("Allow VACA_Vision to appear over TV", "Allow overlays") {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                    .onFailure { Toast.makeText(this, "Use ADB: appops set $packageName SYSTEM_ALERT_WINDOW allow", Toast.LENGTH_LONG).show() }
            }
            return
        }
        // Setup must be reachable before HA/web authentication succeeds.
        // Once a remote is selected or setup is explicitly dismissed, startup stays quiet.
        val remotePrefs = getSharedPreferences("vaca_prefs", MODE_PRIVATE)
        val remoteMissing = remotePrefs.getString("last_remote_mac", null).isNullOrBlank()
        if (remoteMissing && !remotePrefs.getBoolean("ble_prompt_done", false) && !remoteSetupAttempted) {
            remoteSetupAttempted = true
            if (!OverlayService.running) startOverlay()
            startActivity(Intent(this, com.mph1970.vacavision.overlayruntime.ble.BleConnectActivity::class.java))
            return
        }
        if (config.needsSignIn) {
            if (!OverlayService.running) startOverlay()
            if (!signInAttempted) signIn()
            else showPrompt("Sign in to Home Assistant", "Continue to sign-in") { signIn() }
            return
        }
        if (signInAttempted) startOverlay(OverlayService.AUTH_CHANGED)
        startOverlay(if (intent.getBooleanExtra(OPEN_PICKER, false)) OverlayService.PICKER else null)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
    private fun startOverlay(action: String? = null) {
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).setAction(action))
    }
    private fun signIn() {
        signInAttempted = true
        startActivity(Intent(this, DashboardLoginActivity::class.java))
    }
    private fun showPrompt(title: String, action: String, onClick: () -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
            setPadding(64, 48, 64, 48); setBackgroundColor(Color.rgb(17, 28, 37))
        }
        layout.addView(TextView(this).apply { text = title; textSize = 26f; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER })
        val primary = Button(this).apply { text = action; isAllCaps = false; setOnClickListener { onClick() } }
        layout.addView(primary)
        layout.addView(Button(this).apply {
            text = "Connection settings"; isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java).putExtra(SettingsActivity.STARTUP, true)) }
        })
        setContentView(layout); primary.requestFocusFromTouch()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("sign_in_attempted", signInAttempted)
        out.putBoolean("remote_setup_attempted", remoteSetupAttempted)
        super.onSaveInstanceState(out)
    }
    companion object {
        const val BOOT = "boot_start"
        const val OPEN_PICKER = "open_picker"
    }
}
