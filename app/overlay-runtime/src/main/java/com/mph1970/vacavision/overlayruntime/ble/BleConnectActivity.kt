package com.mph1970.vacavision.overlayruntime.ble

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.mph1970.vacavision.core.VacaProtocol
import com.mph1970.vacavision.overlayruntime.overlay.OverlayController

/** Optional, D-pad friendly setup. Selection listens to one remote; it never auto-pairs. */
class BleConnectActivity : Activity() {
    private val ink = Color.rgb(235, 242, 250)
    private val muted = Color.rgb(156, 173, 193)
    private val accent = Color.rgb(91, 218, 224)
    private lateinit var scan: Button
    private lateinit var status: TextView
    private lateinit var saved: TextView
    private lateinit var empty: TextView
    private lateinit var adapter: BaseAdapter
    private val devices = mutableListOf<Remote>()
    private val prefs by lazy { getSharedPreferences("vaca_prefs", MODE_PRIVATE) }
    private var scanInitiated = false
    private lateinit var done: Button
    private val ui = Handler(Looper.getMainLooper())
    private var dismissAt = 0L
    private val countdown = object : Runnable {
        override fun run() {
            if (scanInitiated || isFinishing) return
            val remaining = dismissAt - SystemClock.elapsedRealtime()
            if (remaining <= 0) { finishSetup(); return }
            done.text = "Done (${(remaining + 999) / 1000}s)"
            ui.postDelayed(this, 250)
        }
    }
    private data class Remote(val name: String, val mac: String, val rssi: Int)
    private val permissions get() = if (android.os.Build.VERSION.SDK_INT >= 31)
        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    private fun permitted() = permissions.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun panel(colour: Int, border: Int = colour) = GradientDrawable().apply {
        setColor(colour); cornerRadius = dp(12).toFloat(); setStroke(dp(2), border)
    }
    private fun focusBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), panel(Color.rgb(30, 64, 78), accent))
        addState(intArrayOf(android.R.attr.state_pressed), panel(Color.rgb(30, 64, 78), accent))
        addState(intArrayOf(android.R.attr.state_selected), panel(Color.rgb(30, 64, 78), accent))
        addState(intArrayOf(), panel(Color.rgb(26, 39, 55)))
    }
    private fun label(value: String, size: Float, colour: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(colour)
        setPadding(0, dp(5), 0, dp(5))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 16f; isAllCaps = false; setTextColor(ink)
        background = focusBackground(); minHeight = dp(52); setPadding(dp(20), dp(8), dp(20), dp(8))
        setOnClickListener { action() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(28), dp(48), dp(24))
            setBackgroundColor(Color.rgb(13, 22, 34))
        }
        root.addView(label("VACA_Vision  /  REMOTE SETUP", 13f, accent))
        root.addView(label("Bluetooth remotes", 30f).apply { typeface = Typeface.DEFAULT_BOLD })
        root.addView(label("Choose a remote to control your dashboard picker.", 17f, muted))
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18), 0, dp(18)) }
        val guide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(24), dp(16))
            background = panel(Color.rgb(19, 31, 46))
        }
        guide.addView(label("Find your remote", 20f))
        guide.addView(label("1   Keep the remote near your Fire TV.\n\n2   Press a button on the Bluetooth remote.\n\n3   Select it from the list on the right.", 16f, muted))
        saved = label("", 15f, accent); guide.addView(saved)
        guide.addView(label("This step is optional. You can reopen it from the device configuration in Home Assistant.", 14f, muted))
        body.addView(guide, LinearLayout.LayoutParams(0, -1, 0.42f).apply { rightMargin = dp(24) })
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = label("Ready when you are · choose Scan to begin", 16f, accent); results.addView(status)
        val frame = FrameLayout(this)
        val list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT); dividerHeight = dp(8)
            setSelector(focusBackground()); isFocusable = true; clipToPadding = false
        }
        empty = label("Scan for nearby remotes\nThis page closes in 10 seconds unless you start a scan.", 17f, muted).apply { gravity = Gravity.CENTER }
        frame.addView(list, FrameLayout.LayoutParams(-1, -1)); frame.addView(empty, FrameLayout.LayoutParams(-1, -1)); list.emptyView = empty
        adapter = object : BaseAdapter() {
            override fun getCount() = devices.size
            override fun getItem(position: Int) = devices[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = devices[position]
                return LinearLayout(this@BleConnectActivity).apply {
                    orientation = LinearLayout.VERTICAL; minimumHeight = dp(76); setPadding(dp(16), dp(6), dp(16), dp(6))
                    addView(label(row.name.ifBlank { "Bluetooth remote" }, 18f))
                    val signal = if (row.rssi >= -65) "Strong signal" else if (row.rssi >= -80) "Good signal" else "Weak signal"
                    addView(label("$signal  ·  ${row.mac}", 13f, muted))
                }
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, index, _ -> select(devices[index]) }
        results.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(results, LinearLayout.LayoutParams(0, -1, 0.58f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scan = button("Scan for remotes") { scan() }
        actions.addView(scan)
        done = button("Done (10s)") { finishSetup() }
        actions.addView(done, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        actions.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(button("Forget saved remote") {
            AlertDialog.Builder(this).setTitle("Forget the saved remote?")
                .setMessage("You can select it again by scanning. Your dashboards will stay configured.")
                .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ ->
                    prefs.edit().remove("last_remote_mac").remove("last_remote_name").remove("last_remote_seen").apply()
                    if (permitted()) command(BleRemoteService.ACTION_DISCONNECT) { putExtra(BleRemoteService.EXTRA_FORGET, true) }
                    updateSaved()
                }.show()
        })
        root.addView(actions)
        root.addView(label(VacaProtocol.CREDITS, 11f, muted).apply { setPadding(0, dp(14), 0, 0) })
        setContentView(root); scan.requestFocus()
        dismissAt = SystemClock.elapsedRealtime() + 10_000
    }
    private fun command(action: String, extras: Intent.() -> Unit = {}) {
        ContextCompat.startForegroundService(this, Intent(this, BleRemoteService::class.java).setAction(action).apply(extras))
    }
    private fun scan() {
        scanInitiated = true; ui.removeCallbacks(countdown); done.text = "Done"
        empty.text = "No remotes found yet\nPress a button on your remote to make it visible."
        if (!permitted()) {
            requestPermissions(permissions, 41)
            status.text = "Allow Bluetooth access to find remotes"; return
        }
        gate(true); command(BleRemoteService.ACTION_START_SCAN)
        status.text = "Scanning · press a button on your remote"; scan.text = "Scan again"
    }
    override fun onRequestPermissionsResult(code: Int, names: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, names, results)
        if (code == 41 && permitted()) scan()
    }
    private fun updateSaved() { saved.text = prefs.getString("last_remote_name", null)?.let { "Saved remote: $it" } ?: "No saved remote" }
    private fun select(remote: Remote) {
        if (!permitted()) { scan(); return }
        prefs.edit().putString("last_remote_mac", remote.mac).putString("last_remote_name", remote.name)
            .putLong("last_remote_seen", System.currentTimeMillis()).apply()
        command(BleRemoteService.ACTION_CONNECT_MAC) { putExtra(BleRemoteService.EXTRA_MAC, remote.mac); putExtra(BleRemoteService.EXTRA_NAME, remote.name) }
        finishSetup(BleRemoteService.REASON_LISTENING_SELECTED)
    }
    private fun finishSetup(reason: String = BleRemoteService.REASON_SKIPPED) {
        gate(false); prefs.edit().putBoolean("ble_prompt_done", true).putBoolean("ble_disabled", false).apply()
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(BleRemoteService.ACTION_CONNECTION_STATE)
            .putExtra(BleRemoteService.EXTRA_CONNECTED, false).putExtra(BleRemoteService.EXTRA_REASON, reason))
        setResult(RESULT_OK); finish()
    }
    @Deprecated("Legacy Android back handling")
    override fun onBackPressed() { finishSetup() }
    private fun gate(enabled: Boolean) { if (permitted()) command(BleRemoteService.ACTION_SET_CONN_GATE) { putExtra(BleRemoteService.EXTRA_GATE, enabled) } }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BleRemoteService.ACTION_SCAN_RESULT -> {
                    val mac = intent.getStringExtra(BleRemoteService.EXTRA_MAC) ?: return
                    val remote = Remote(intent.getStringExtra(BleRemoteService.EXTRA_NAME) ?: "Bluetooth remote", mac, intent.getIntExtra(BleRemoteService.EXTRA_RSSI, -100))
                    val index = devices.indexOfFirst { it.mac == mac }
                    if (index >= 0) devices[index] = remote else devices.add(remote)
                    adapter.notifyDataSetChanged(); status.text = "${devices.size} found · select a remote to use it"
                }
                BleRemoteService.ACTION_SCAN_DONE -> { status.text = "Scan complete · ${devices.size} remotes found" }
            }
        }
    }
    override fun onStart() {
        super.onStart(); OverlayController.setPickerInputEnabled(false); updateSaved()
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, IntentFilter().apply {
            addAction(BleRemoteService.ACTION_SCAN_RESULT); addAction(BleRemoteService.ACTION_SCAN_DONE)
        })
        if (!scanInitiated) ui.post(countdown)
    }
    override fun onStop() {
        ui.removeCallbacks(countdown)
        gate(false); OverlayController.setPickerInputEnabled(true)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver); super.onStop()
    }
}
