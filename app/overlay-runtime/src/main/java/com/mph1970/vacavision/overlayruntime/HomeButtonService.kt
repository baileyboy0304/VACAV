package com.mph1970.vacavision.overlayruntime

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.mph1970.vacavision.overlayruntime.overlay.OverlayController

/** Opt-in HOME observer. Never consumes keys or retrieves screen content. */
class HomeButtonService : AccessibilityService() {
    public override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_HOME && event.action == KeyEvent.ACTION_UP) {
            android.util.Log.i("VACA_Vision", "Home listener: dismissing dashboard and picker")
            OverlayController.dismissForHome()
        }
        return false // Fire TV still receives HOME and every other key unchanged.
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
