package com.mph1970.vacavision.overlayruntime

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mph1970.vacavision.overlayruntime.settings.APPConfig

/** Short-lived, signature-protected entry point for the companion launcher. */
class PickerControlActivity : Activity() {
    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (!com.msp1974.vacompanion.DeviceMode.isOverlay(this)) {
            startActivity(Intent(this, com.msp1974.vacompanion.MainActivity::class.java))
            finish(); return
        }
        if (!Settings.canDrawOverlays(this)) {
            // Initial setup is exceptional; normal toggles never launch the settings UI.
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.OPEN_PICKER, true))
        } else {
            APPConfig.getInstance(this).enabled = true
            ContextCompat.startForegroundService(this,
                Intent(this, OverlayService::class.java).setAction(OverlayService.TOGGLE_PICKER))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
