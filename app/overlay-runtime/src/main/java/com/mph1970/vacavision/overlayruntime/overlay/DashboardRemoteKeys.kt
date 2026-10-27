package com.mph1970.vacavision.overlayruntime.overlay

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.view.KeyEvent

internal object DashboardRemoteKeys {
    fun action(code: Int): String? = when(code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "select"
        KeyEvent.KEYCODE_BACK -> "back"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "play_pause"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "next"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> "previous"
        KeyEvent.KEYCODE_MEDIA_STOP -> "stop"
        else -> null
    }
    fun fire(event: KeyEvent, action: String) = if (action == "back") event.action == KeyEvent.ACTION_UP && !event.isCanceled
        else event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || action in listOf("left", "right", "up", "down"))
}
