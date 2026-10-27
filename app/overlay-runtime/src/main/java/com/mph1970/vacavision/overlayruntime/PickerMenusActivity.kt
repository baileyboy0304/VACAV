package com.mph1970.vacavision.overlayruntime

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.mph1970.vacavision.overlayruntime.overlay.OverlayController
import com.mph1970.vacavision.overlayruntime.overlay.OverlayLayout
import com.mph1970.vacavision.overlayruntime.picker.DevicePickerStore
import com.mph1970.vacavision.overlayruntime.settings.APPConfig
import com.mph1970.vacavision.overlayruntime.web.DashboardChrome
import com.mph1970.vacavision.overlayruntime.web.DashboardWebView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Full-display live preview. Toolbar navigation never enters the dashboard itself. */
class PickerMenusActivity : RemoteActivity() {
    private data class Entry(val path: String, val title: String, val icon: String = "mdi:view-dashboard")
    private enum class Mode { NONE, SIZE, POSITION, DURATION }
    private lateinit var config: APPConfig
    private lateinit var store: DevicePickerStore
    private lateinit var origin: String
    private lateinit var device: String
    private lateinit var root: FrameLayout
    private lateinit var preview: FrameLayout
    private lateinit var web: DashboardWebView
    private lateinit var hint: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var active: Button
    private lateinit var size: Button
    private lateinit var position: Button
    private lateinit var horizontal: Button
    private lateinit var vertical: Button
    private var visibilityButton: Button? = null
    private var iconButton: Button? = null
    private var durationButton: Button? = null
    private var loadingButton: Button? = null
    private var draftLoading = com.mph1970.vacavision.overlayruntime.picker.DashboardLoading.SELECTED
    private var savedLoading = draftLoading
    private var draftIcon = ""
    private var savedIcon = ""
    private var presetMode = false
    private var draftHidden = false
    private var savedHidden = false
    private var draftName = ""
    private var savedName = ""
    private var newPreset = false
    private val buttons = mutableListOf<Button>()
    private var entries = emptyList<Entry>()
    private var index = 0
    private var mode = Mode.NONE
    private var draft = OverlayLayout()
    private var saved = draft
    private var notice = ""
    private var ready = false
    private val dirty: Boolean get() = draftLoading != savedLoading || draft.durationMs != saved.durationMs || draftIcon != savedIcon || newPreset || draftName != savedName || draftHidden != savedHidden || draft.horizontal != saved.horizontal || draft.vertical != saved.vertical ||
        abs(draft.width-saved.width) > .00001f || abs(draft.height-saved.height) > .00001f ||
        abs(draft.x-saved.x) > .00001f || abs(draft.y-saved.y) > .00001f
    private val current: Entry get() = entries[index]
    private val editingName get() = if (presetMode) draftName else current.title
    private fun loadSaved() {
        saved = if (presetMode) store.presets().first { it.id == current.path }.layout else store.layout(current.path)
        draft = saved
        savedName = current.title; draftName = savedName
        savedHidden = !presetMode && store.hidden(current.path); draftHidden = savedHidden
        savedIcon = if (presetMode) "" else store.iconOverride(current.path); draftIcon = savedIcon
        savedLoading = if (presetMode) com.mph1970.vacavision.overlayruntime.picker.DashboardLoading.SELECTED else store.loading(current.path)
        draftLoading = savedLoading
        newPreset = false
    }
    private fun loadPreview() { if (!presetMode) web.openEditorDashboard(current.path) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        hideSystemBars()
        config = APPConfig.getInstance(this)
        origin = config.haUrl; device = config.uuid
        store = DevicePickerStore(this, origin, device)
        presetMode = intent.getBooleanExtra("presets", false)
        entries = runCatching {
            if (presetMode) store.presets().map { Entry(it.id, it.name) } else {
                val rows = store.read()
                (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
                    val title = row.getString("title")
                    Entry(row.getString("path"), title, com.mph1970.vacavision.overlayruntime.picker.HaPickerIcons.defaultIcon(this, title, row.optString("icon")))
                } }
            }
        }.getOrDefault(emptyList())
        root = FrameLayout(this)
        setContentView(root)
        root.addView(object : View(this) {
            private val paint = Paint().apply { color = 0xff23313b.toInt(); strokeWidth = 1f }
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(0xff101820.toInt())
                for (x in 0..width step dp(48).coerceAtLeast(1)) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paint)
                for (y in 0..height step dp(48).coerceAtLeast(1)) canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
            }
        }, FrameLayout.LayoutParams(-1, -1))
        if (entries.isEmpty()) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(32), dp(32), dp(32)) }
            box.addView(TextView(this).apply { text = "No dashboard views yet. Use Populate picker in Settings first."; textSize = 22f; setTextColor(Color.WHITE) })
            box.addView(Button(this).apply { text = "Back to settings"; setOnClickListener { finish() }; requestFocusFromTouch() })
            root.addView(box, FrameLayout.LayoutParams(-1, -1))
            return
        }
        index = entries.indexOfFirst { it.path == state?.getString("path") }.coerceAtLeast(0)
        loadSaved()
        if (presetMode && state?.getBoolean("newPreset") == true && state.getString("origin") == origin) {
            entries = entries + Entry(state.getString("path")!!, state.getString("draftName")!!)
            index = entries.lastIndex; newPreset = true
        }
        draft = if (state?.getString("origin") == origin) runCatching {
            OverlayLayout.fromJson(current.path, JSONObject(state.getString("draft")!!))
        }.getOrDefault(saved) else saved
        if (state?.getString("origin") == origin) {
            draftHidden = state.getBoolean("hidden", savedHidden)
            draftName = state.getString("draftName", savedName)
            draftIcon = state.getString("draftIcon", savedIcon)
            draftLoading = com.mph1970.vacavision.overlayruntime.picker.DashboardLoading.parse(state.getString("loading", savedLoading.value), current.path)
        }
        mode = runCatching { Mode.valueOf(state?.getString("mode") ?: "NONE") }.getOrDefault(Mode.NONE)
        preview = FrameLayout(this).apply {
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // Match the running overlay's corner clipping without reserving border space.
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(24).toFloat() }
            clipToOutline = true
        }
        root.addView(preview)
        if (!presetMode) {
        web = DashboardWebView(this) { message ->
            notice = if (message == "Dashboard authenticated") "" else message
            if (ready) refreshUi()
        }.apply {
            isFocusable = false; isFocusableInTouchMode = false
            settings.mediaPlaybackRequiresUserGesture = true
            setOnTouchListener { _, _ -> true }
            onNavigate = { DashboardChrome.apply(this) }
            onSignInRequired = { notice = "Sign in to Home Assistant from Settings."; refreshUi() }
            onRendererGone = { notice = "Preview renderer stopped. Reopen the editor to reload."; refreshUi() }
        }
        preview.addView(web, FrameLayout.LayoutParams(-1, -1))
        } else {
            preview.addView(TextView(this).apply {
                text = "Preset window"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                setBackgroundColor(0x99326c77.toInt())
            }, FrameLayout.LayoutParams(-1, -1))
        }
        preview.foreground = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(2), 0xff59d4c7.toInt()); cornerRadius = dp(24).toFloat() }
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(0xee101820.toInt()); cornerRadius = dp(12).toFloat() }
            elevation = dp(12).toFloat()
        }
        var row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        toolbar.addView(row)
        fun button(label: String, weight: Float = 1f, click: () -> Unit): Button = Button(this).apply {
            text = label; textSize = 16f; isAllCaps = false; setTextColor(Color.WHITE)
            isFocusable = true; isFocusableInTouchMode = true
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            minWidth = 0; minimumWidth = 0; setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { click() }
            setOnFocusChangeListener { _, _ -> if (ready) refreshUi() }
            row.addView(this, LinearLayout.LayoutParams(0, dp(64), weight).apply { setMargins(dp(3), 0, dp(3), 0) })
            buttons += this
        }
        active = button("View", 1.65f) { changeView(1) }
        size = button("Window size", 1.2f) { toggleMode(Mode.SIZE) }
        position = button("Window position", 1.2f) { toggleMode(Mode.POSITION) }
        horizontal = button("Horizontal") { draft = draft.cycleHorizontal(); notice = ""; refreshUi() }
        vertical = button("Vertical") { draft = draft.cycleVertical(); notice = ""; refreshUi() }
        row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; setPadding(0, dp(6), 0, 0) }
        toolbar.addView(row)
        if (presetMode) {
            button("New preset") { leave {
                // Discard an unsaved new entry before creating another one.
                if (newPreset) entries = entries.filterIndexed { i, _ -> i != index }
                entries = entries + Entry(java.util.UUID.randomUUID().toString(), "Preset ${entries.size + 1}")
                index = entries.lastIndex; draft = OverlayLayout(); saved = draft
                draftName = current.title; savedName = ""; newPreset = true; mode = Mode.NONE
                refreshUi(); renamePreset()
            } }
            button("Rename") { renamePreset() }
        } else {
            visibilityButton = button("Picker: shown") { draftHidden = !draftHidden; notice = ""; refreshUi() }
            button("Preset") { choosePreset() }
            iconButton = button("Icon") {
                trackDialog(com.mph1970.vacavision.overlayruntime.picker.PickerIconDialog(this, draftIcon.ifBlank { current.icon }, current.title) { selected ->
                    draftIcon = selected; notice = "Icon changed — Save to keep"; refreshUi()
                })
            }
            durationButton = button("Duration", 1.3f) { toggleMode(Mode.DURATION) }
            loadingButton = button("Loading", 1.3f) { chooseLoading() }
        }
        button("Save", .65f) { save() }
        button("Exit", .65f) { leave { finish() } }
        hint = TextView(this).apply { textSize = 14f; setTextColor(0xffc8d9df.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        toolbar.addView(hint)
        root.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(20), 0, dp(20), dp(20)) })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (ready) applyPreviewBounds() }
        ready = true
        buttons.getOrElse(state?.getInt("focus", 0) ?: 0) { active }.requestFocusFromTouch()
        refreshUi()
        loadPreview()
    }

    private fun applyPreviewBounds() {
        if (root.width <= 0 || root.height <= 0) return
        val above = draft.top()
        val below = 1f - draft.top() - draft.height
        val barGravity = if (above > below + .02f) Gravity.TOP else Gravity.BOTTOM
        (toolbar.layoutParams as FrameLayout.LayoutParams).let { params ->
            if (params.gravity != barGravity) {
                params.gravity = barGravity
                params.topMargin = dp(20); params.bottomMargin = dp(20)
                toolbar.layoutParams = params
            }
        }
        val b = draft.bounds(root.width, root.height)
        val old = preview.layoutParams as? FrameLayout.LayoutParams
        if (old?.width == b.width && old.height == b.height && old.leftMargin == b.x && old.topMargin == b.y) return
        preview.layoutParams = FrameLayout.LayoutParams(b.width, b.height, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = b.x; topMargin = b.y
        }
    }
    private fun refreshUi() {
        if (!ready) return
        applyPreviewBounds()
        fun percent(value: Float) = "${(value * 100).roundToInt()}%"
        active.text = "${if (presetMode) "Preset" else "View"} ${index + 1}/${entries.size}\n$editingName"
        visibilityButton?.text = if (draftHidden) "Picker: hidden" else "Picker: shown"
        loadingButton?.text = "Loading\n${draftLoading.label}"
        iconButton?.let { button ->
            val icon = draftIcon.ifBlank { current.icon }
            button.text = "Icon\n${if (draftIcon.isBlank()) "Default" else icon.substringAfter(':')}"
            val drawable = com.mph1970.vacavision.overlayruntime.picker.HaPickerIcons.drawable(this, icon)
            drawable?.setBounds(0, 0, dp(28), dp(28))
            button.setCompoundDrawables(drawable, null, null, null)
        }
        size.text = "Window size\n${percent(draft.width)} × ${percent(draft.height)}"
        durationButton?.text = "Duration\n" + when {
            draft.durationMs == 0L -> "Until dismissed"
            draft.durationMs % 1000L == 0L -> "${draft.durationMs / 1000L}s"
            else -> "${draft.durationMs / 1000.0}s"
        }
        position.text = "Window position\n${percent(draft.left())}, ${percent(draft.top())}"
        fun anchor(name: String) = if (name == "FREE") name else name.lowercase().replaceFirstChar { it.uppercase() }
        horizontal.text = "Horizontal\n${anchor(draft.horizontal.name)}"
        vertical.text = "Vertical\n${anchor(draft.vertical.name)}"
        val editingButton = when(mode) { Mode.SIZE -> size; Mode.POSITION -> position; Mode.DURATION -> durationButton; Mode.NONE -> null }
        buttons.forEach { button ->
            button.isFocusable = editingButton == null || button === editingButton
            val selected = button.hasFocus()
            button.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (button === editingButton) 0xff176a65.toInt() else if (selected) 0xff17434a.toInt() else 0xff26353e.toInt())
                setStroke(dp(2), if (selected) 0xff59d4c7.toInt() else 0xff334852.toInt())
            }
        }
        hint.text = when {
            notice.isNotBlank() -> notice
            mode == Mode.SIZE -> "SIZE  ·  ←/→ width  ·  ↑/↓ height  ·  Select to finish"
            mode == Mode.POSITION -> "POSITION  ·  Arrow keys move (moving an axis sets FREE)  ·  Select to finish"
            mode == Mode.DURATION -> "DURATION  ·  ↑/↓ ±1s  ·  →/← ±10s  ·  0 = until dismissed  ·  Select to finish"
            else -> "←/→ controls  ·  ${if (presetMode) "Preset" else "View"}: ↑/↓ switch  ·  Select to edit  ·  Back to settings"
        } + if (dirty) "  ·  Unsaved" else ""
    }
    private fun toggleMode(next: Mode) {
        mode = if (mode == next) Mode.NONE else next
        notice = ""; refreshUi()
    }
    private fun save(): Boolean {
        return try {
            check(config.haUrl == origin && config.uuid == device) { "Home Assistant changed. Reopen the editor." }
            if (presetMode) {
                store.savePreset(current.path, draftName, draft)
                draftName = draftName.trim()
                entries = entries.mapIndexed { i, entry -> if (i == index) entry.copy(title = draftName) else entry }
                newPreset = false
            } else store.saveLayout(current.path, draft, draftHidden, draftIcon, durationEdited = draft.durationMs != saved.durationMs, loading = draftLoading)
            saved = draft
            savedHidden = draftHidden; savedName = draftName
            savedIcon = draftIcon
            savedLoading = draftLoading
            notice = "Saved $editingName"
            refreshUi()
            true
        } catch (e: Exception) {
            notice = if (presetMode) "Could not save: ${e.message}" else "Could not save. Check the view still exists and reopen Settings. Your edits are still here."
            refreshUi()
            false
        }
    }
    private fun leave(action: () -> Unit) {
        if (!dirty) { action(); return }
        if (remoteDialog?.isShowing == true) return
        val dialog = AlertDialog.Builder(this).setTitle("Save changes to $editingName?")
            .setMessage("There are unsaved changes.")
            .setPositiveButton("Save", null).setNegativeButton("Discard") { _, _ -> action() }
            .setNeutralButton("Cancel", null).create()
        remoteDialog = dialog
        dialog.setOnDismissListener { if (remoteDialog === dialog) remoteDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (save()) { dialog.dismiss(); action() } }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).requestFocusFromTouch()
        }
        dialog.show()
    }
    private fun changeView(step: Int) {
        if (entries.size <= 1) return
        leave {
            if (newPreset) {
                entries = entries.filterIndexed { i, _ -> i != index }
                index = if (step > 0) -1 else entries.size
            }
            index = Math.floorMod(index + step, entries.size)
            loadSaved(); mode = Mode.NONE; notice = if (presetMode) "" else "Loading ${current.title}…"
            active.requestFocusFromTouch(); refreshUi(); loadPreview()
        }
    }
    private fun trackDialog(dialog: android.app.Dialog) {
        remoteDialog = dialog
        dialog.setOnDismissListener { if (remoteDialog === dialog) remoteDialog = null }
        dialog.show()
    }
    private fun choosePreset() {
        val presets = runCatching { store.presets() }.getOrElse { notice = "Could not read presets"; refreshUi(); return }
        val dialog = AlertDialog.Builder(this).setTitle("Apply preset")
            .setItems(presets.map { it.name }.toTypedArray()) { _, which ->
                draft = presets[which].layout.copy(durationMs = draft.durationMs)
                notice = "Applied ${presets[which].name} — Save to keep"; refreshUi()
            }.setNegativeButton("Cancel", null).create()
        trackDialog(dialog)
        dialog.listView.requestFocusFromTouch()
        dialog.listView.setSelection(0)
    }
    private fun chooseLoading() {
        val choices = com.mph1970.vacavision.overlayruntime.picker.DashboardLoading.entries
        val dialog = AlertDialog.Builder(this).setTitle("Dashboard loading")
            .setSingleChoiceItems(choices.map { "${it.label}\n${it.description}" }.toTypedArray(), choices.indexOf(draftLoading)) { dialog, which ->
                draftLoading = choices[which]; notice = "Loading changed — Save to keep"; refreshUi(); dialog.dismiss()
            }.setNegativeButton("Cancel", null).create()
        trackDialog(dialog)
        dialog.listView.requestFocusFromTouch()
    }
    private fun renamePreset() {
        val input = EditText(this).apply { setText(draftName); isSingleLine = true; filters = arrayOf(android.text.InputFilter.LengthFilter(60)); selectAll() }
        val dialog = AlertDialog.Builder(this).setTitle("Preset name").setView(input)
            .setPositiveButton("Apply", null).setNegativeButton("Cancel", null).create()
        trackDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) input.error = "Enter a name" else { draftName = name; notice = ""; refreshUi(); dialog.dismiss() }
        }
        input.requestFocusFromTouch()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!ready) return super.dispatchKeyEvent(event)
        remoteDialog?.takeIf { it.isShowing }?.let { return it.dispatchKeyEvent(event) }
        val arrows = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        val select = event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        if (event.keyCode !in arrows && !select && event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.repeatCount == 0) onBackPressed()
            return true
        }
        if (select) {
            if (event.repeatCount == 0) {
                if (mode != Mode.NONE) { mode = Mode.NONE; notice = ""; refreshUi() }
                else (root.findFocus() as? Button)?.performClick()
            }
            return true
        }
        if (mode != Mode.NONE) {
            if (mode == Mode.DURATION) {
                val seconds = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> 1L
                    KeyEvent.KEYCODE_DPAD_DOWN -> -1L
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 10L
                    else -> -10L
                }
                draft = draft.copy(durationMs = (draft.durationMs / 1000L + seconds).coerceIn(0L, 86_400L) * 1000L)
                notice = ""; refreshUi(); return true
            }
            val dx = when(event.keyCode) { KeyEvent.KEYCODE_DPAD_RIGHT -> .01f; KeyEvent.KEYCODE_DPAD_LEFT -> -.01f; else -> 0f }
            val dy = when(event.keyCode) { KeyEvent.KEYCODE_DPAD_DOWN -> .01f; KeyEvent.KEYCODE_DPAD_UP -> -.01f; else -> 0f }
            draft = if (mode == Mode.SIZE) draft.resize(dx, -dy) else draft.move(dx, dy)
            notice = ""; refreshUi(); return true
        }
        if (root.findFocus() === active && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (event.repeatCount == 0) changeView(if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            buttons[Math.floorMod(buttons.indexOf(root.findFocus()) + step, buttons.size)].requestFocusFromTouch()
        }
        return true
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!ready) { super.onBackPressed(); return }
        if (mode != Mode.NONE) { mode = Mode.NONE; notice = ""; refreshUi() }
        else leave { finish() }
    }
    override fun onStart() { super.onStart(); if (ready) OverlayController.setEditorVisible(true) }
    override fun onStop() { if (ready) OverlayController.setEditorVisible(false); super.onStop() }
    override fun onResume() { super.onResume(); if (::web.isInitialized) web.onResume() }
    override fun onPause() { if (::web.isInitialized) web.onPause(); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
        if (hasFocus && ready && root.findFocus() !is Button) {
            when (mode) { Mode.SIZE -> size; Mode.POSITION -> position; Mode.DURATION -> durationButton ?: active; Mode.NONE -> active }.requestFocusFromTouch()
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        if (ready) {
            out.putString("origin", origin); out.putString("path", current.path); out.putString("draft", draft.toJson().toString())
            out.putBoolean("hidden", draftHidden); out.putString("draftName", draftName); out.putBoolean("newPreset", newPreset)
            out.putString("draftIcon", draftIcon)
            out.putString("loading", draftLoading.value)
            out.putString("mode", mode.name); out.putInt("focus", buttons.indexOf(root.findFocus()).coerceAtLeast(0))
        }
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() {
        remoteDialog?.dismiss()
        if (::web.isInitialized) { (web.parent as? ViewGroup)?.removeView(web); web.stopLoading(); web.destroy() }
        super.onDestroy()
    }
}

