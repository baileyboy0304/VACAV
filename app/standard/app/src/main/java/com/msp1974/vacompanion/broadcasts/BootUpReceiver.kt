package com.msp1974.vacompanion.broadcasts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import com.msp1974.vacompanion.MainActivity
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber

class BootUpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Timber.d("Received boot completed intent")
            val sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            val startOnBoot = sharedPreferences.getBoolean("startOnBoot", APPConfig.DEFAULT_START_ON_BOOT)
            if (startOnBoot) {
                Timber.d("Starting app")
                val activityIntent = Intent(context, MainActivity::class.java)
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(activityIntent)
            }
        }
    }
}