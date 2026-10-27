package com.mph1970.vacavision.overlayruntime.overlay

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.mph1970.vacavision.overlayruntime.ble.BleRemoteService
import org.json.JSONArray
import org.json.JSONObject

/** A separate small window: dashboard fade timers cannot dismiss an alarm. */
class AlarmOverlay(private val context: Context, private val changed: (JSONObject) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("overlay_alarms", Context.MODE_PRIVATE)
    private val alarms = linkedMapOf<String, JSONObject>()
    private val dismissed = prefs.getStringSet("dismissed", emptySet())!!.toMutableSet()
    private var card: LinearLayout? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (active && intent.getStringExtra(BleRemoteService.EXTRA_KEY) in listOf("select", "back")) dismissFirst()
        }
    }
    init {
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, IntentFilter(BleRemoteService.ACTION_BUTTON))
        runCatching {
            val saved = JSONArray(prefs.getString("pending", "[]"))
            for (i in 0 until saved.length().coerceAtMost(20)) {
                val alarm = saved.getJSONObject(i)
                val id = alarm.optString("id")
                if (id.isNotBlank() && id !in dismissed) alarms[id] = alarm
            }
        }
        render()
    }

    fun action(payload: JSONObject) {
        val id = payload.optString("id").ifBlank { "alarm" }
        if (payload.optBoolean("activate", true)) {
            if (id in dismissed) { publish(); return }
            if (alarms.size >= 20 && id !in alarms) {
                android.util.Log.w("VACA_Vision", "Alarm queue full")
                return
            }
            alarms[id] = JSONObject(payload.toString()).put("id", id)
        } else {
            val ids = if (payload.has("id")) listOf(id) else alarms.keys.toList()
            ids.forEach { if (payload.optBoolean("notify", true)) markDismissed(it) else alarms.remove(it) }
        }
        savePending()
        render()
        publish()
    }

    fun acknowledge(id: String) {
        dismissed.remove(id)
        saveDismissed()
    }

    private fun markDismissed(id: String) {
        if (alarms.remove(id) != null) {
            dismissed.add(id)
            saveDismissed()
            savePending()
        }
    }

    private fun saveDismissed() { prefs.edit().putStringSet("dismissed", dismissed.toSet()).apply() }
    private fun savePending() { prefs.edit().putString("pending", JSONArray(alarms.values.toList()).toString()).apply() }

    private fun dismissFirst() {
        alarms.keys.firstOrNull()?.let { markDismissed(it) }
        suppressPickerUntil = SystemClock.uptimeMillis() + 350
        render()
        publish()
    }

    fun status(): JSONObject = JSONObject().put("alarm", alarms.isNotEmpty())
        .put("alarm_count", alarms.size).put("dismissed_alarm_ids", JSONArray(dismissed.toList()))
    private fun publish() { changed(status()) }

    private fun render() {
        card?.let { runCatching { manager.removeView(it) } }; card = null
        active = alarms.isNotEmpty()
        val alarm = alarms.values.firstOrNull() ?: return
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val view = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)) {
                    if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) dismissFirst()
                    return true
                }
                // Keep navigation presses inside the popup; volume/media keys retain their meaning.
                if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply { setColor(Color.parseColor("#EB172432")); cornerRadius = dp(16).toFloat(); setStroke(dp(2), Color.parseColor("#FFBF47")) }
            isFocusableInTouchMode = true
            contentDescription = "Alarm popup"
        }
        fun label(text: String, size: Float) {
            view.addView(TextView(context).apply { this.text = text; textSize = size; setTextColor(Color.WHITE); maxLines = 4 })
        }
        label(alarm.optString("title", "Alarm").take(80), 22f)
        label(alarm.optString("message", "Time is up").take(500), 18f)
        label("Select or Back to dismiss${if (alarms.size > 1) " · ${alarms.size} alarms" else ""}", 14f)
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(dp(360).coerceAtMost(context.resources.displayMetrics.widthPixels / 2),
            WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(18); y = dp(58)
        }
        try {
            manager.addView(view, params); card = view; view.requestFocus()
        } catch (e: RuntimeException) {
            active = false
            android.util.Log.e("VACA_Vision", "Cannot show alarm popup: ${e.javaClass.simpleName}")
        }
    }

    override fun close() {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        card?.let { runCatching { manager.removeView(it) } }; card = null
        active = false
    }
    companion object {
        var active = false; private set
        private var suppressPickerUntil = 0L
        val blocksPicker: Boolean get() = active || SystemClock.uptimeMillis() < suppressPickerUntil
    }
}
