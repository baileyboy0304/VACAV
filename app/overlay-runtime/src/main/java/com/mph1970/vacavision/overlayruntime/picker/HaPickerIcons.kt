package com.mph1970.vacavision.overlayruntime.picker

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import org.json.JSONObject

/** HA's standard MDI identifiers, with no network dependency when opening the picker. */
object HaPickerIcons {
    @Volatile private var catalog: JSONObject? = null
    private val suggested = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun catalog(context: Context): JSONObject = catalog ?: synchronized(this) {
        catalog ?: context.assets.open("mdi-paths.json").bufferedReader().use { JSONObject(it.readText()) }
            .also { catalog = it }
    }

    fun names(context: Context): List<String> = catalog(context).keys().asSequence().map { "mdi:$it" }.sorted().toList()

    /** Preserve HA's explicit icon; replace only the generic dashboard placeholder. */
    fun defaultIcon(context: Context, title: String, configured: String): String {
        val generic = configured.isBlank() || configured == "ic_dashboard_placeholder" ||
            configured in setOf("mdi:view-dashboard", "hass:view-dashboard", "hassio:view-dashboard", "hademo:view-dashboard")
        if (!generic) return configured
        return suggested.getOrPut(title) {
            PickerIconDialog.filterNames(names(context), title, "All").firstOrNull() ?: "mdi:view-dashboard"
        }
    }

    fun drawable(context: Context, icon: String): Drawable? {
        val parts = icon.split(':', limit = 2)
        if (parts.size != 2 || parts[0] !in setOf("mdi", "hass", "hassio", "hademo")) return null
        val data = catalog(context).optString(parts[1]).takeIf { it.isNotBlank() } ?: return null
        val path = PathParser.createPathFromPathData(data) ?: return null
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            private val scaledPath = Path()
            override fun onBoundsChange(bounds: Rect) {
                val size = minOf(bounds.width(), bounds.height()).toFloat()
                val matrix = Matrix().apply {
                    setScale(size / 24f, size / 24f)
                    postTranslate(bounds.left + (bounds.width() - size) / 2, bounds.top + (bounds.height() - size) / 2)
                }
                path.transform(matrix, scaledPath)
            }
            override fun draw(canvas: Canvas) {
                canvas.drawPath(scaledPath, paint)
            }
            // Old Fire OS hardware renderers rasterize small paths before applying canvas scale.
            // Transform the geometry at a TV-friendly size first, then let ImageView fit it down.
            override fun getIntrinsicWidth() = 512
            override fun getIntrinsicHeight() = 512
            override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
            @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }
}
