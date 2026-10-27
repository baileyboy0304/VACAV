package com.mph1970.vacavision.overlayruntime.overlay

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import org.json.JSONObject
import java.net.URI
import kotlin.math.roundToInt

enum class HorizontalAnchor { FREE, RIGHT, MIDDLE, LEFT }
enum class VerticalAnchor { FREE, TOP, MIDDLE, BOTTOM }

/** Fractions of the full display, shared by the live editor and WindowManager host. */
data class OverlayLayout(
    val width: Float = .5f, val height: Float = .5f,
    val x: Float = .25f, val y: Float = .5f,
    val horizontal: HorizontalAnchor = HorizontalAnchor.MIDDLE,
    val vertical: VerticalAnchor = VerticalAnchor.BOTTOM,
    val durationMs: Long = 10_000L
) {
    fun normalized(): OverlayLayout {
        val w = width.safe(.5f).coerceIn(.1f, 1f)
        val h = height.safe(.5f).coerceIn(.1f, 1f)
        return copy(width = w, height = h, x = x.safe(0f).coerceIn(0f, 1f - w),
            y = y.safe(0f).coerceIn(0f, 1f - h), durationMs = durationMs.coerceIn(0L, 86_400_000L))
    }
    fun left(): Float = when (horizontal) {
        HorizontalAnchor.FREE -> x.coerceIn(0f, 1f - width)
        HorizontalAnchor.RIGHT -> 1f - width
        HorizontalAnchor.MIDDLE -> (1f - width) / 2
        HorizontalAnchor.LEFT -> 0f
    }
    fun top(): Float = when (vertical) {
        VerticalAnchor.FREE -> y.coerceIn(0f, 1f - height)
        VerticalAnchor.TOP -> 0f
        VerticalAnchor.MIDDLE -> (1f - height) / 2
        VerticalAnchor.BOTTOM -> 1f - height
    }
    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)
    fun bounds(screenWidth: Int, screenHeight: Int): Bounds {
        val layout = normalized()
        val w = (screenWidth * layout.width).roundToInt().coerceIn(1, screenWidth.coerceAtLeast(1))
        val h = (screenHeight * layout.height).roundToInt().coerceIn(1, screenHeight.coerceAtLeast(1))
        return Bounds((screenWidth * layout.left()).roundToInt().coerceIn(0, (screenWidth-w).coerceAtLeast(0)),
            (screenHeight * layout.top()).roundToInt().coerceIn(0, (screenHeight-h).coerceAtLeast(0)), w, h)
    }
    fun resize(dw: Float, dh: Float) = copy(width = width + dw, height = height + dh).normalized()
    fun move(dx: Float, dy: Float) = copy(x = left() + dx, y = top() + dy,
        horizontal = if (dx != 0f) HorizontalAnchor.FREE else horizontal,
        vertical = if (dy != 0f) VerticalAnchor.FREE else vertical).normalized()
    fun cycleHorizontal() = copy(x = left(), horizontal = HorizontalAnchor.entries[(horizontal.ordinal + 1) % 4]).normalized()
    fun cycleVertical() = copy(y = top(), vertical = VerticalAnchor.entries[(vertical.ordinal + 1) % 4]).normalized()
    fun toJson(): JSONObject = JSONObject().put("width", width).put("height", height).put("x", x).put("y", y)
        .put("horizontal", horizontal.name).put("vertical", vertical.name).put("durationMs", durationMs)

    companion object {
        private fun Float.safe(fallback: Float) = if (isFinite()) this else fallback
        /** Exact route identity; queries and fragments do not change the saved layout. */
        fun path(url: String): String = runCatching { URI(url).path }.getOrNull().orEmpty()
        fun viewName(url: String): String = path(url).trimEnd('/').substringAfterLast('/')
        fun isCameraView(path: String): Boolean = viewName(path).lowercase().let { it == "beachcam" || "camera" in it || "webcam" in it }
        // Compatibility defaults only. Saved per-view layouts always take precedence.
        fun defaults(path: String): OverlayLayout = when (viewName(path)) {
            "weather" -> OverlayLayout(1f, .65f)
            "tides" -> OverlayLayout(1f, .65f, vertical = VerticalAnchor.MIDDLE)
            "camera" -> OverlayLayout(.9f, .9f, vertical = VerticalAnchor.MIDDLE, durationMs = 30_000)
            "music", "newmusic" -> OverlayLayout(1f, 1f, vertical = VerticalAnchor.MIDDLE, durationMs = 0)
            "thermostat" -> OverlayLayout(.25f, .25f, horizontal = HorizontalAnchor.RIGHT)
            "locate" -> OverlayLayout(.75f, .75f, vertical = VerticalAnchor.MIDDLE, durationMs = 15_000)
            "find" -> OverlayLayout(.75f, .75f, vertical = VerticalAnchor.MIDDLE, durationMs = 0)
            "clock" -> OverlayLayout(.26f, .18f, horizontal = HorizontalAnchor.RIGHT, vertical = VerticalAnchor.TOP, durationMs = 100)
            else -> OverlayLayout(durationMs = if (isCameraView(path)) 30_000 else 10_000)
        }.normalized()
        fun fromJson(path: String, value: JSONObject?): OverlayLayout {
            val base = defaults(path)
            if (value == null) return base
            return base.copy(width = value.optDouble("width", base.width.toDouble()).toFloat(),
                height = value.optDouble("height", base.height.toDouble()).toFloat(),
                x = value.optDouble("x", base.x.toDouble()).toFloat(), y = value.optDouble("y", base.y.toDouble()).toFloat(),
                horizontal = runCatching { HorizontalAnchor.valueOf(value.getString("horizontal")) }.getOrDefault(base.horizontal),
                vertical = runCatching { VerticalAnchor.valueOf(value.getString("vertical")) }.getOrDefault(base.vertical),
                durationMs = value.optLong("durationMs", base.durationMs)).normalized()
        }
    }
}
