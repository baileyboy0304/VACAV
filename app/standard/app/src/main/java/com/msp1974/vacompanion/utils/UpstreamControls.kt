package com.msp1974.vacompanion.utils

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.msp1974.vacompanion.MainActivity
import com.msp1974.vacompanion.R
import org.json.JSONObject
import kotlin.math.roundToInt

/** UI and audio focus only. EDA is the sole recorder and audio renderer. */
class UpstreamControls(
    private val context: Context,
    private val brightness: (Int) -> Unit,
    private val keepScreenOn: () -> Boolean,
    private val onKeepScreenOnChanged: (Boolean) -> Unit,
    private val result: (JSONObject) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var root: LinearLayout? = null
    private var keepOn: LinearLayout? = null
    private var brightnessControl: SeekBar? = null
    private var controlsVisible = false
    private val playerProbe = StreamingPlayerProbe(context)
    private var touchGeneration = 0
    private val hideControls = Runnable { setControlsVisible(false) }
    private val density get() = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private val accent = Color.rgb(255, 198, 109)
    private val lease = VoiceLease()
    private val session get() = lease.session
    private var focus: AudioFocusRequest? = null
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS) handler.post { end("failed") }
    }
    private val expiry = object : Runnable {
        override fun run() {
            if (lease.expired(SystemClock.elapsedRealtime())) end("expired")
            if (controlsVisible) playerProbe.check { if (!it) setControlsVisible(false) }
            if (root != null || session.isNotEmpty()) handler.postDelayed(this, 1000)
        }
    }

    fun start() = handler.post {
        updateKeepOn()
        if (root != null || !Settings.canDrawOverlays(context)) return@post
        val panel = object : LinearLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    // Android has already targeted the underlying app: observing this
                    // notification neither consumes nor replays its original touch.
                    val generation = ++touchGeneration
                    if (controlsVisible) setControlsVisible(false)
                    else playerProbe.check { eligible ->
                        if (generation == touchGeneration && eligible) setControlsVisible(true)
                    }
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) handler.removeCallbacks(hideControls)
                val handled = super.dispatchTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    scheduleHide()
                }
                return handled
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0f
        }
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(controls)
        controls.addView(tile("Home", R.drawable.controls_home, "Return to the dashboard") {
            setControlsVisible(false)
            context.startActivity(Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        })
        keepOn = tile("Keep on", R.drawable.controls_keep_on, "Keep dashboard screen on") {
            onKeepScreenOnChanged(!keepScreenOn())
            updateKeepOn()
        }.also { controls.addView(it) }
        controls.addView(tile("Brightness", R.drawable.controls_brightness, "Adjust screen brightness") {
            brightnessControl?.let { bar ->
                bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        })
        // Round each tile's position separately, as the dashboard's CSS grid does.
        // Rounding every gap separately accumulates a visible offset across the row.
        for (index in 0 until controls.childCount) {
            (controls.getChildAt(index).layoutParams as LinearLayout.LayoutParams).apply {
                marginEnd = 0
                marginStart = if (index == 0) 0 else dp(index * 104) - dp((index - 1) * 104) - dp(96)
            }
        }
        brightnessControl = SeekBar(context).apply {
            visibility = View.GONE
            background = tileBackground(false)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            max = 100
            isEnabled = Settings.System.canWrite(context)
            contentDescription = "Screen brightness"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) { if (user) brightness(value.coerceAtLeast(5)) }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }.also { panel.addView(it, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }) }
        updateKeepOn()
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        // A transparent, non-touchable 1px window still receives ACTION_OUTSIDE on
        // Fire OS. It leaves the entire screen usable while our controls are hidden.
        val params = WindowManager.LayoutParams(1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = dp(22)
        params.setTitle("Vision touch controls")
        try { wm.addView(panel, params); root = panel; handler.post(expiry) }
        catch (_: RuntimeException) { root = null }
    }

    private fun tileBackground(active: Boolean) = GradientDrawable().apply {
        setColor(if (active) Color.rgb(91, 65, 31) else Color.rgb(49, 42, 35))
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1).coerceAtLeast(1), if (active) accent else Color.rgb(111, 88, 58))
    }

    private fun tile(label: String, icon: Int, description: String, action: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(96), dp(88)).apply { marginEnd = dp(8) }
        background = tileBackground(false)
        isFocusable = true
        contentDescription = description
        addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        addView(TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(Color.rgb(255, 241, 220))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        setOnClickListener { action(); scheduleHide() }
    }

    private fun updateKeepOn() {
        keepOn?.apply {
            val enabled = keepScreenOn()
            background = tileBackground(enabled)
            isSelected = enabled
            contentDescription = "Keep dashboard screen on: ${if (enabled) "on" else "off"}"
            (getChildAt(1) as TextView).text = if (enabled) "Keep on ✓" else "Keep on"
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (controlsVisible) handler.postDelayed(hideControls, 5000)
    }

    private fun setControlsVisible(visible: Boolean) {
        val panel = root ?: return
        controlsVisible = visible
        panel.alpha = if (visible) 1f else 0f
        val params = panel.layoutParams as WindowManager.LayoutParams
        val count = (panel.getChildAt(0) as LinearLayout).childCount
        params.width = if (visible) dp((count - 1) * 104) + dp(96) else 1
        params.height = if (visible) WindowManager.LayoutParams.WRAP_CONTENT else 1
        params.flags = if (visible) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (visible) {
            updateKeepOn()
            brightnessControl?.progress = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
        } else brightnessControl?.visibility = View.GONE
        wm.updateViewLayout(panel, params)
        scheduleHide()
    }

    fun command(action: String, value: JSONObject) = handler.post {
        val token = value.optString("session")
        when (action) {
            "voice-begin" -> {
                end(null)
                if (token.isEmpty()) return@post
                lease.begin(token, SystemClock.elapsedRealtime())
                val granted = if (Build.VERSION.SDK_INT >= 26) {
                    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener(listener, handler).build()
                    focus = request
                    audio.requestAudioFocus(request)
                } else audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { end("failed"); return@post }
                lease.renew(token, SystemClock.elapsedRealtime())
                handler.removeCallbacks(expiry); handler.post(expiry)
            }
            "voice-keepalive" -> lease.renew(token, SystemClock.elapsedRealtime())
            "voice-end" -> if (token == session) end(null)
        }
    }

    private fun end(reason: String?) {
        val previous = lease.end()
        if (Build.VERSION.SDK_INT >= 26) focus?.let { audio.abandonAudioFocusRequest(it) }
        else audio.abandonAudioFocus(listener)
        focus = null
        if (reason != null && previous.isNotEmpty()) result(JSONObject().put("session", previous).put("playback", reason))
    }

    fun disconnected() = handler.post { end("disconnected") }
    fun refresh() = handler.post { updateKeepOn() }
    fun close() = handler.post {
        touchGeneration++
        playerProbe.close()
        end("closed"); handler.removeCallbacks(expiry)
        handler.removeCallbacks(hideControls)
        root?.let { runCatching { wm.removeView(it) } }; root = null; keepOn = null
        brightnessControl = null; controlsVisible = false
    }
}
