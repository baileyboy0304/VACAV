package com.mph1970.vacavision.overlayruntime.picker

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.content.Context
import android.util.AtomicFile
import com.mph1970.vacavision.overlayruntime.settings.APPConfig
import com.mph1970.vacavision.overlayruntime.overlay.OverlayLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Private to this installation and HA server. No shared www file or packaged defaults. */
class DevicePickerStore(context: Context, origin: String = APPConfig.getInstance(context).haUrl,
                        deviceId: String = APPConfig.getInstance(context).uuid) {
    private val appContext = context.applicationContext
    private val key = MessageDigest.getInstance("SHA-256")
        .digest("$deviceId\n${origin.trimEnd('/')}\nview-assist".toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val file = AtomicFile(File(context.filesDir, "picker-$key.json"))
    private val presetFile = AtomicFile(File(context.filesDir, "picker-presets-$key.json"))

    data class LayoutPreset(val id: String, val name: String, val layout: OverlayLayout)
    fun presets(): List<LayoutPreset> = synchronized(lock) {
        root().optJSONArray("presets")?.let { return@synchronized presetRows(it) }
        val data = try { presetFile.openRead().use { it.readBytes().toString(Charsets.UTF_8) } }
            catch (_: java.io.FileNotFoundException) { return@synchronized listOf(
                LayoutPreset("full", "Full screen", OverlayLayout(1f, 1f)),
                LayoutPreset("bottom", "Bottom banner", OverlayLayout(1f, .35f)),
                LayoutPreset("center", "Centered", OverlayLayout(.7f, .7f, vertical = com.mph1970.vacavision.overlayruntime.overlay.VerticalAnchor.MIDDLE))
            ) }
        val rows = JSONObject(data).also { check(it.getInt("version") == 1) }.getJSONArray("presets")
        presetRows(rows)
    }
    private fun presetRows(rows: JSONArray) = (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
        LayoutPreset(row.getString("id"), row.getString("name"), OverlayLayout.fromJson("", row.getJSONObject("layout")))
    } }
    fun savePreset(id: String, name: String, layout: OverlayLayout) = synchronized(lock) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 60) { "Enter a name (1–60 characters)." }
        val items = presets().toMutableList()
        require(items.none { it.id != id && it.name.equals(clean, true) }) { "That preset name is already in use." }
        val preset = LayoutPreset(id, clean, layout.normalized())
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) items.add(preset) else items[index] = preset
        val rows = JSONArray()
        items.forEach { rows.put(JSONObject().put("id", it.id).put("name", it.name).put("layout", it.layout.toJson())) }
        writeRoot(root().put("presets", rows))
    }
    fun hidden(path: String): Boolean {
        val rows = read()
        return (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("path") == path }?.optBoolean("hidden", false) ?: false
    }

    fun read(): JSONArray = synchronized(lock) {
        root().getJSONArray("views")
    }
    private fun root(): JSONObject {
        val data = try { file.openRead().use { it.readBytes().toString(Charsets.UTF_8) } }
            catch (_: java.io.FileNotFoundException) { return JSONObject().put("version", 1).put("views", JSONArray()) }
        val root = JSONObject(data)
        check(root.getInt("version") == 1) { "Unsupported picker storage version" }
        return root
    }

    data class Refresh(val total: Int, val added: Int, val removed: Int)

    fun refresh(discovered: List<DiscoveredView>): Refresh = synchronized(lock) {
        require(discovered.map { it.path }.distinct().size == discovered.size)
        val old = read()
        val previous = (0 until old.length()).associate { old.getJSONObject(it).let { row -> row.getString("path") to row } }
        val rows = JSONArray()
        discovered.forEach { view ->
            // Copy the whole record: future overlay templates, visibility and icon overrides survive.
            val row = previous[view.path]?.let { JSONObject(it.toString()) } ?: JSONObject()
            row.put("id", view.path).put("path", view.path).put("title", view.title)
                .put("icon", view.icon).put("subview", view.subview)
            rows.put(row)
        }
        write(rows)
        val paths = discovered.map { it.path }.toSet()
        Refresh(paths.size, paths.count { it !in previous }, previous.keys.count { it !in paths })
    }

    fun layout(path: String): OverlayLayout {
        val route = OverlayLayout.path(path)
        val rows = read()
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("path") == route }
        val overlay = row?.optJSONObject("overlay")
        val layout = OverlayLayout.fromJson(route, overlay)
        // Older editor versions persisted implicit timeouts along with geometry. Correct only
        // those old defaults; explicit durations chosen in the duration editor always win.
        if (overlay != null && !overlay.optBoolean("durationEdited", false)) {
            val oldDefault = when (OverlayLayout.viewName(route)) {
                "music", "newmusic", "find" -> 15_000L
                "clock" -> 10_000L
                "camera" -> 30_000L
                else -> if (OverlayLayout.isCameraView(route)) 10_000L else null
            }
            if (layout.durationMs == oldDefault) return layout.copy(durationMs = OverlayLayout.defaults(route).durationMs)
        }
        return layout
    }

    fun iconOverride(path: String): String {
        val rows = read()
        return (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("path") == path }?.optString("iconOverride").orEmpty()
    }

    fun loading(path: String): DashboardLoading {
        val route = OverlayLayout.path(path)
        val rows = read()
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("path") == route }
        return DashboardLoading.parse(row?.optString("loading"), route)
    }

    fun saveLayout(path: String, layout: OverlayLayout, hidden: Boolean? = null, iconOverride: String? = null, durationEdited: Boolean = false,
                   loading: DashboardLoading? = null) = synchronized(lock) {
        val rows = read()
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("path") == path }
            ?: error("This view was removed. Populate the picker again.")
        val overlay = row.optJSONObject("overlay") ?: JSONObject()
        val values = layout.normalized().toJson()
        values.keys().forEach { overlay.put(it, values.get(it)) }
        if (durationEdited) overlay.put("durationEdited", true)
        row.put("overlay", overlay)
        if (loading != null) row.put("loading", loading.value)
        if (hidden != null) row.put("hidden", hidden)
        if (iconOverride != null) {
            if (iconOverride.isBlank()) row.remove("iconOverride") else row.put("iconOverride", iconOverride)
        }
        write(rows)
    }

    fun exportProfile(): JSONObject = synchronized(lock) {
        val views = JSONArray(); val layouts = JSONArray()
        val rows = read()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val path = row.getString("path")
            views.put(JSONObject().put("id", path).put("path", path).put("title", row.getString("title"))
                .put("icon", portableIcon(row.optString("icon"))).put("subview", row.optBoolean("subview"))
                .put("hidden", row.optBoolean("hidden"))
                .put("iconOverride", portableIcon(row.optString("iconOverride").ifBlank { HaPickerIcons.defaultIcon(appContext, row.getString("title"), row.optString("icon")) }))
                .put("loading", loading(path).value).put("overlay", layout(path).toJson().put("durationEdited", true)))
        }
        presets().forEach { layouts.put(JSONObject().put("id", it.id).put("name", it.name).put("layout", it.layout.normalized().toJson())) }
        PickerProfile.validate(PickerProfile.create(views, layouts))
    }
    fun applyProfile(profile: JSONObject) = synchronized(lock) {
        val clean = PickerProfile.validate(profile)
        val previous = exportProfile()
        // Menu, presets and restore point commit together in one AtomicFile.
        writeRoot(JSONObject().put("version", 1).put("views", clean.getJSONArray("views"))
            .put("presets", clean.getJSONArray("presets")).put("previous", previous))
    }
    fun hasRestorePoint(): Boolean = synchronized(lock) { root().has("previous") }
    private fun portableIcon(value: String) = value.replace(Regex("^(hass|hassio|hademo):"), "mdi:")
        .takeIf { Regex("mdi:[a-z0-9-]+").matches(it) }.orEmpty()
    fun restorePrevious() = synchronized(lock) {
        val previous = root().optJSONObject("previous") ?: error("No previous picker has been saved.")
        applyProfile(previous)
    }
    private fun write(rows: JSONArray) = writeRoot(root().put("views", rows))
    private fun writeRoot(root: JSONObject) {
        val encoded = root.toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try { output.write(encoded); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    companion object { private val lock = Any() }
}

data class DiscoveredView(val path: String, val title: String, val icon: String, val subview: Boolean)

object DashboardViews {
    fun parse(config: JSONObject): List<DiscoveredView> {
        val views = config.optJSONArray("views")
            ?: error("This dashboard has no explicit view list (it may use a strategy). Picker unchanged.")
        require(views.length() <= 500) { "Too many dashboard views. Picker unchanged." }
        val seen = hashSetOf<String>()
        return (0 until views.length()).map { index ->
            val view = views.getJSONObject(index)
            val slug = if (view.isNull("path")) index.toString() else view.getString("path")
            // A view path is one route segment. Never import external URLs, fragments or traversal.
            require(slug.isNotBlank() && slug !in listOf(".", "..") &&
                slug.none { it.isWhitespace() || it.isISOControl() || it in "/\\?#%:" }) {
                "A dashboard has an invalid view path. Picker unchanged."
            }
            val path = "/view-assist/$slug"
            require(seen.add(path)) { "Duplicate dashboard view paths. Picker unchanged." }
            DiscoveredView(path, view.optString("title").ifBlank { slug.replace('-', ' ').replace('_', ' ') },
                view.optString("icon").ifBlank { "mdi:view-dashboard" }, view.optBoolean("subview", false))
        }
    }
}
