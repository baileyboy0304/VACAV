package com.mph1970.vacavision.overlayruntime.picker

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Portable allowlisted data: never preferences, tokens, device IDs or BLE/player configuration. */
object PickerProfile {
    const val FORMAT = "vacaoverlay-picker"
    const val MAX_BYTES = 524288
    fun validate(input: JSONObject): JSONObject {
        require(input.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Profile is too large (maximum 512 KiB)." }
        keys(input, setOf("format", "version", "views", "presets"))
        require(input.getString("format") == FORMAT && input.get("version") == 1) { "Unsupported picker profile version." }
        val views = input.getJSONArray("views"); val presets = input.getJSONArray("presets")
        require(views.length() <= 256 && presets.length() <= 64) { "Too many views or presets." }
        val paths = HashSet<String>(); val ids = HashSet<String>(); val names = HashSet<String>()
        for (i in 0 until views.length()) {
            val row = views.getJSONObject(i)
            keys(row, setOf("id", "path", "title", "icon", "subview", "hidden", "iconOverride", "overlay", "loading"))
            val path = text(row, "path", 512)
            val uri = runCatching { URI(path) }.getOrNull()
            require(uri != null && path.startsWith("/") && !path.startsWith("//") && '\\' !in path &&
                uri.scheme == null && uri.rawQuery == null && uri.rawFragment == null && uri.normalize().path == uri.path &&
                '%' !in path && path.none { it.isWhitespace() } && paths.add(path)) { "Invalid or duplicate dashboard path." }
            require(text(row, "id", 512) == path) { "View ID must match its path." }
            text(row, "title", 160)
            icon(row, "icon"); if (row.has("iconOverride")) icon(row, "iconOverride")
            bool(row, "hidden"); bool(row, "subview")
            require(row.getString("loading") in setOf("selected", "picker", "keep_alive")) { "Unknown loading behaviour." }
            layout(row.getJSONObject("overlay"), true)
        }
        for (i in 0 until presets.length()) {
            val row = presets.getJSONObject(i); keys(row, setOf("id", "name", "layout"))
            require(ids.add(text(row, "id", 80)) && names.add(text(row, "name", 60).lowercase(java.util.Locale.ROOT))) { "Duplicate preset." }
            layout(row.getJSONObject("layout"), false)
        }
        return JSONObject(input.toString())
    }
    private fun keys(o: JSONObject, allowed: Set<String>) {
        require(o.keys().asSequence().all { it in allowed }) { "Profile contains unsupported fields." }
    }
    private fun text(o: JSONObject, key: String, max: Int, blank: Boolean = false): String {
        val value = o.get(key)
        require(value is String && value.length <= max && (blank || value.isNotBlank()) && value.none { it.code < 32 }) { "Invalid $key." }
        return value
    }
    private fun bool(o: JSONObject, key: String) { require(o.get(key) is Boolean) { "Invalid $key." } }
    private fun icon(o: JSONObject, key: String) {
        require(Regex("(?:mdi:[a-z0-9-]+)?").matches(text(o, key, 120, true))) { "Use an mdi icon name." }
    }
    private fun layout(o: JSONObject, view: Boolean) {
        keys(o, setOf("width", "height", "x", "y", "horizontal", "vertical", "durationMs") + if (view) setOf("durationEdited") else emptySet())
        for (key in listOf("width", "height", "x", "y")) {
            val n = o.get(key); require(n is Number && n.toDouble().isFinite() && n.toDouble() in (if (key in listOf("width", "height")) .1 else 0.0)..1.0) { "Invalid layout $key." }
        }
        require(o.getDouble("x") + o.getDouble("width") <= 1.000001 && o.getDouble("y") + o.getDouble("height") <= 1.000001) { "Layout is outside the screen." }
        require(o.getString("horizontal") in setOf("FREE", "LEFT", "MIDDLE", "RIGHT") && o.getString("vertical") in setOf("FREE", "TOP", "MIDDLE", "BOTTOM")) { "Invalid gravity." }
        val duration = o.get("durationMs")
        require(duration is Number && duration.toDouble().isFinite() && duration.toDouble() == duration.toLong().toDouble() && duration.toLong() in 0..86_400_000) { "Invalid duration." }
        if (view) { bool(o, "durationEdited"); require(o.getBoolean("durationEdited")) { "Profile durations must be explicit." } }
    }
    fun create(views: JSONArray, presets: JSONArray) = JSONObject().put("format", FORMAT).put("version", 1).put("views", views).put("presets", presets)
}
