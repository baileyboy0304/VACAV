package com.mph1970.vacavision.overlayruntime

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import com.mph1970.vacavision.overlayruntime.picker.DevicePickerStore
import com.mph1970.vacavision.overlayruntime.picker.PickerProfileClient
import com.mph1970.vacavision.overlayruntime.settings.APPConfig
import kotlinx.coroutines.*
import org.json.JSONObject

/** TV-friendly named snapshots; sharing never changes device identity or connection settings. */
class PickerProfilesActivity : RemoteActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var config: APPConfig
    private lateinit var status: TextView
    private lateinit var form: LinearLayout
    private lateinit var origin: String
    private lateinit var device: String
    private var busy = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        config = APPConfig.getInstance(this); origin = config.haUrl; device = config.uuid
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(100, 32, 100, 32); setBackgroundColor(Color.rgb(17, 28, 37))
        }
        setContentView(ScrollView(this).apply { addView(form) })
        label("Picker profiles", 28f)
        label("Save to Home Assistant, then load on any Overlay device. Loading replaces this device’s picker and presets.", 18f)
        button("Save picker to Home Assistant") { chooseSave() }.requestFocusFromTouch()
        button("Load picker from Home Assistant") { chooseLoad() }
        button("Restore previous picker") {
            confirm("Restore previous picker?", "Replace the current picker with its saved restore point.") {
                work { local { it.restorePrevious() }; changed(); status.text = "Previous picker restored." }
            }
        }
        button("Back") { finish() }
        status = label("Requires Overlay integration 1.1.0 or later. Profiles and JSON files can also be managed in Home Assistant → Overlay pickers.", 16f)
    }
    private fun chooseSave() = work {
        val profiles = PickerProfileClient.request("list").getJSONArray("profiles")
        val labels = arrayOf("New profile…") + (0 until profiles.length()).map { "Update ${profiles.getJSONObject(it).getString("name")}" }
        dialog(AlertDialog.Builder(this@PickerProfilesActivity).setTitle("Save picker").setItems(labels) { _, which ->
            if (which == 0) nameProfile() else {
                val row = profiles.getJSONObject(which - 1)
                confirm("Update ${row.getString("name")}?", "Replace this saved profile with this device’s current picker. Other devices keep their own copies.") {
                    saveProfile(row.getString("name"), row)
                }
            }
        }.setNegativeButton("Cancel", null))
    }
    private fun nameProfile() {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.ROOT).format(java.util.Date())
        val input = EditText(this).apply { setSingleLine(true); setText("${config.deviceName.take(35)} $stamp"); selectAll() }
        dialog(AlertDialog.Builder(this).setTitle("Profile name").setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> saveProfile(input.text.toString().trim(), null) })
    }
    private fun saveProfile(name: String, existing: JSONObject?) = work {
        require(name.isNotBlank() && name.length <= 60) { "Use a name of 1–60 characters." }
        val request = JSONObject().put("name", name).put("profile", local { it.exportProfile() })
        existing?.let { request.put("id", it.getString("id")).put("revision", it.getInt("revision")) }
        PickerProfileClient.request("save", request)
        status.text = "Saved ‘$name’ to Home Assistant."
    }
    private fun chooseLoad() = work {
        val rows = PickerProfileClient.request("list").getJSONArray("profiles")
        if (rows.length() == 0) { status.text = "No profiles saved yet. Save one from this TV or another Overlay device."; return@work }
        dialog(AlertDialog.Builder(this@PickerProfilesActivity).setTitle("Load picker profile")
            .setItems((0 until rows.length()).map { rows.getJSONObject(it).getString("name") }.toTypedArray()) { _, which ->
                val row = rows.getJSONObject(which)
                work {
                    val item = PickerProfileClient.request("get", JSONObject().put("id", row.getString("id")))
                    val profile = item.getJSONObject("profile")
                    confirm("Load ${item.getString("name")}?", "Replace this device’s picker with ${profile.getJSONArray("views").length()} views and ${profile.getJSONArray("presets").length()} presets. A restore point will be saved first.") {
                        work { local { it.applyProfile(profile) }; changed(); status.text = "Profile loaded. The previous picker can be restored." }
                    }
                }
            }.setNegativeButton("Cancel", null))
    }
    private suspend fun <T> local(block: (DevicePickerStore) -> T): T = withContext(Dispatchers.IO) {
        check(origin == config.haUrl && device == config.uuid) { "Device settings changed. Reopen this page." }
        block(DevicePickerStore(this@PickerProfilesActivity, origin, device))
    }
    private fun changed() {
        if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.PICKER_CHANGED))
    }
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; buttons(false); status.text = "Working…"
            try { block() }
            catch (_: TimeoutCancellationException) { status.text = "No reply from Home Assistant. Check the saved profiles before retrying." }
            catch (e: Exception) { if (e is CancellationException) throw e; status.text = e.message ?: "Could not complete the picker operation." }
            finally { busy = false; buttons(true) }
        }
    }
    private fun buttons(enabled: Boolean) { for (i in 0 until form.childCount) (form.getChildAt(i) as? Button)?.isEnabled = enabled }
    private fun confirm(title: String, message: String, yes: () -> Unit) = dialog(AlertDialog.Builder(this).setTitle(title).setMessage(message)
        .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> yes() })
    private fun dialog(builder: AlertDialog.Builder) {
        val d = builder.create(); remoteDialog = d
        d.setOnDismissListener { if (remoteDialog === d) remoteDialog = null }; d.show()
    }
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0, 12, 0, 12); form.addView(this)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; setOnClickListener { action() }; form.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
