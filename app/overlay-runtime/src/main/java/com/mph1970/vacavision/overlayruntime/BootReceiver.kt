package com.mph1970.vacavision.overlayruntime

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mph1970.vacavision.overlayruntime.settings.APPConfig

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && APPConfig.getInstance(context).enabled) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java)) }
            val remotePrefs = context.getSharedPreferences("vaca_prefs", Context.MODE_PRIVATE)
            val needsRemoteSetup = remotePrefs.getString("last_remote_mac", null).isNullOrBlank() && !remotePrefs.getBoolean("ble_prompt_done", false)
            if (APPConfig.getInstance(context).needsSignIn || needsRemoteSetup || !android.provider.Settings.canDrawOverlays(context)) {
                runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.BOOT, true)) }
            }
        }
    }
}
