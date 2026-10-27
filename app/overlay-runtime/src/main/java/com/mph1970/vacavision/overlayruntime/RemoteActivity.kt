package com.mph1970.vacavision.overlayruntime

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.app.Activity
import android.content.*
import android.view.KeyEvent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.mph1970.vacavision.overlayruntime.ble.BleRemoteService

/** The advertisement remote can operate native settings as well as the overlay picker. */
open class RemoteActivity : Activity() {
    companion object {
        private var resumed = java.lang.ref.WeakReference<RemoteActivity>(null)
        /** A recreated background overlay must not compete with a native settings page for BLE keys. */
        val handlesRemote: Boolean get() = resumed.get() != null
        val pickerEditorOpen: Boolean get() = resumed.get() is PickerMenusActivity
    }
    protected var remoteDialog: android.app.Dialog? = null
    private val remote = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (com.mph1970.vacavision.overlayruntime.overlay.AlarmOverlay.blocksPicker) return
            val command = intent.getStringExtra(BleRemoteService.EXTRA_KEY)
            if (command == "prev" || command == "next") {
                val root = remoteDialog?.window?.decorView ?: window.decorView
                val direction = if (command == "next") android.view.View.FOCUS_FORWARD else android.view.View.FOCUS_BACKWARD
                android.view.FocusFinder.getInstance().findNextFocus(root as android.view.ViewGroup, root.findFocus(), direction)?.requestFocusFromTouch()
                return
            }
            val key = when (command) {
                "select" -> KeyEvent.KEYCODE_DPAD_CENTER
                "back" -> KeyEvent.KEYCODE_BACK
                else -> return
            }
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(action, key)
                remoteDialog?.dispatchKeyEvent(event) ?: dispatchKeyEvent(event)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        resumed = java.lang.ref.WeakReference(this)
        LocalBroadcastManager.getInstance(this).registerReceiver(remote, IntentFilter(BleRemoteService.ACTION_BUTTON))
    }
    override fun onPause() {
        if (resumed.get() === this) resumed.clear()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(remote)
        super.onPause()
    }
}
