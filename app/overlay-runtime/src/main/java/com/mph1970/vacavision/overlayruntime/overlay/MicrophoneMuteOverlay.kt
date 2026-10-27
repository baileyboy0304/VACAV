package com.mph1970.vacavision.overlayruntime.overlay

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.mph1970.vacavision.core.RemoteMicrophone

/** Non-interactive mute line, independent of dashboard/picker visibility. Main thread only. */
class MicrophoneMuteOverlay(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val line = View(context).apply {
        setBackgroundColor(RemoteMicrophone.BAR_COLOR)
        contentDescription = "Microphone muted"
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        (RemoteMicrophone.BAR_HEIGHT_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.BOTTOM or Gravity.LEFT }
    private var attached = false
    fun show(muted: Boolean) {
        if (muted && !attached) { manager.addView(line, params); attached = true }
        else if (!muted) close()
    }
    override fun close() {
        if (attached) { manager.removeViewImmediate(line); attached = false }
    }
}
