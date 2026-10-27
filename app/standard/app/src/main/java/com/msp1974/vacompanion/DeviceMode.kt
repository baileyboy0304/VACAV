package com.msp1974.vacompanion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mph1970.vacavision.overlayruntime.OverlayService
import com.mph1970.vacavision.overlayruntime.ble.BleRemoteService
import com.msp1974.vacompanion.service.VAForegroundService

/** Installers select a runtime, not a different APK. Existing ES8 preferences are untouched. */
object DeviceMode {
    const val EXTRA = "vacav_mode"
    fun selected(context: Context): String = context.getSharedPreferences("vacav_mode", Context.MODE_PRIVATE)
        .getString("mode", null) ?: defaultMode(Build.MANUFACTURER, Build.MODEL)
    fun defaultMode(manufacturer: String, model: String): String =
        if (manufacturer.equals("Amazon", true) && model.startsWith("AFT", true)) "overlay" else "standard"
    fun isOverlay(context: Context) = selected(context) == "overlay"
    fun accept(context: Context, intent: Intent?) {
        val mode = intent?.getStringExtra(EXTRA) ?: return
        if (mode != "standard" && mode != "overlay") return
        context.getSharedPreferences("vacav_mode", Context.MODE_PRIVATE).edit().putString("mode", mode).commit()
        if (mode == "overlay") context.stopService(Intent(context, VAForegroundService::class.java))
        else {
            context.stopService(Intent(context, OverlayService::class.java))
            context.stopService(Intent(context, BleRemoteService::class.java))
        }
    }
    fun route(activity: Activity, intent: Intent?): Boolean {
        accept(activity, intent)
        if (!isOverlay(activity)) return false
        activity.startActivity(Intent(activity, com.mph1970.vacavision.overlayruntime.MainActivity::class.java)
            .putExtra("open_picker", intent?.getBooleanExtra("open_picker", false) ?: false))
        activity.finish()
        return true
    }
}
