package com.mph1970.vacavision.overlayruntime.web

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mph1970.vacavision.overlayruntime.overlay.OverlayLayout
import com.mph1970.vacavision.overlayruntime.picker.DevicePickerStore
import com.mph1970.vacavision.overlayruntime.picker.DashboardLoading
import com.mph1970.vacavision.overlayruntime.settings.APPConfig
import org.json.JSONTokener
import kotlin.math.roundToInt

/** Live data pages and a prepared camera card; camera video runs only during picker/presentation. */
class PreparedDashboards(
    private val context: Context,
    private val controller: DashboardWebView,
    private val status: (String) -> Unit,
    private val reveal: (String, DashboardWebView, OverlayLayout) -> Unit,
) : AutoCloseable {
    private data class Page(val path: String, val web: DashboardWebView, var layout: OverlayLayout, val loading: DashboardLoading,
                            val window: FrameLayout, val params: WindowManager.LayoutParams,
                            var ready: Boolean = false, var stable: Int = 0, var checking: Boolean = false,
                            var retryAt: Long = 0, var generation: Int = 0, var streamEnabled: Boolean = false)
    private val main = Handler(Looper.getMainLooper())
    private val config = APPConfig.getInstance(context)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val parking = FrameLayout(context)
    private val pages = linkedMapOf<String, Page>()
    @Volatile private var request: String? = null
    @Volatile private var visible: String? = null
    @Volatile private var pickerOpen = false
    @Volatile private var warmUntil = 0L
    @Volatile private var warmPaths: Set<String> = emptySet()
    private fun videoWanted(path: String) = (path == request || path == visible) ||
        (path in warmPaths && (pickerOpen || request != null || SystemClock.uptimeMillis() < warmUntil))
    private fun selectWarmPaths(preferred: String? = null) {
        val choices = targets().filter { DevicePickerStore(context).loading(it) == DashboardLoading.PICKER }
        warmPaths = (listOfNotNull(preferred) + warmPaths + choices).filter { it in choices }.distinct().take(2).toSet()
    }
    private val releaseWarmPages = Runnable {
        warmUntil = 0L
        setCameraActive()
        releasePickerPages(request ?: visible)
    }
    private var closed = false
    @Volatile private var state = "null"
    private var snapshotPending = false
    private var controllerRetryAt = 0L
    val active: Boolean get() = request != null || visible != null
    val activePath: String? get() = request ?: visible
    fun visibleWeb(path: String): DashboardWebView? = pages[path]?.web?.takeIf { visible == path }
    private val size get() = android.util.DisplayMetrics().also {
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (closed) return
            if (SystemClock.uptimeMillis() >= controllerRetryAt) {
                controllerRetryAt = SystemClock.uptimeMillis() + 30_000
                controller.retryFailedStartup()
            }
            if (!snapshotPending) {
                snapshotPending = true
                controller.evaluateJavascript(PreparedPageScript.snapshot) { raw ->
                    snapshotPending = false
                    if (!closed) state = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: "null"
                }
            }
            pages.values.toList().forEach(::check)
            main.postDelayed(this, if (request != null) 100 else if (pickerOpen) 200 else 1000)
        }
    }

    init {
        require(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        val screen = size
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // Kept attached, at the final viewport size, wholly beyond the right edge.
        // A video SurfaceView cannot leak over the picker from this separate window.
        val params = WindowManager.LayoutParams(screen.widthPixels, screen.heightPixels, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = screen.widthPixels + 64; y = 0 }
        wm.addView(parking, params)
        park(controller, OverlayLayout(.25f, .25f))
        refresh()
        main.post(ticker)
    }

    private fun targets(): List<String> = runCatching {
        val rows = DevicePickerStore(context).read()
        (0 until rows.length()).map { rows.getJSONObject(it).getString("path") }
            .filter { DevicePickerStore(context).loading(it) != DashboardLoading.SELECTED }
    }.getOrDefault(emptyList())

    fun refresh() {
        val wanted = targets().toSet()
        pages.keys.filter { it != visible && (it !in wanted || pages[it]?.loading != DevicePickerStore(context).loading(it)) }.toList().forEach(::discard)
        wanted.filter { DevicePickerStore(context).loading(it) == DashboardLoading.KEEP_ALIVE ||
            OverlayLayout.viewName(it) == "beachcam" || (pickerOpen && it in warmPaths) || it == request || it == visible }.forEach(::ensure)
    }

    fun handles(path: String) = OverlayLayout.path(path) in targets()

    fun pickerOpened() {
        selectWarmPaths(request ?: visible)
        main.removeCallbacks(releaseWarmPages)
        warmUntil = 0L
        request = null; visible = null; pickerOpen = true
        refresh()
        parkAll()
        setCameraActive()
        releasePickerPages()
        wakeChecks()
    }

    fun dismiss() {
        main.removeCallbacks(releaseWarmPages)
        warmUntil = 0L
        request = null; visible = null; pickerOpen = false
        parkAll()
        setCameraActive()
        releasePickerPages()
    }

    fun show(path: String) {
        val route = OverlayLayout.path(path)
        selectWarmPaths(route)
        request = route; visible = null; pickerOpen = false
        parkAll()
        // Keep prepared picker pages briefly while browsing. Back and another
        // camera selection reuse the existing decoder and first rendered frame.
        main.removeCallbacks(releaseWarmPages)
        warmUntil = 0L
        setCameraActive()
        releasePickerPages(route)
        val page = ensure(route)
        if (page.ready) present(page) else status("Preparing dashboard $route")
        wakeChecks()
    }

    private fun wakeChecks() { main.removeCallbacks(ticker); main.post(ticker) }

    private fun setCameraActive() {
        pages.values.filter { it.loading == DashboardLoading.PICKER }.forEach { page ->
            val enabled = videoWanted(page.path)
            if (page.streamEnabled == enabled) return@forEach
            page.streamEnabled = enabled
            page.ready = false; page.stable = 0; page.generation++
            page.web.evaluateJavascript("window.__vacaSetCameraActive?.($enabled)") { count ->
                if (!closed && pages[page.path] === page) status("Prepared camera ${page.path} frames ${if (enabled) "started" else "unloaded"}: $count")
            }
        }
    }

    fun reload() { pages.values.forEach { it.ready = false; it.stable = 0; it.web.reload() } }

    private fun releasePickerPages(except: String? = null) {
        pages.values.filter { it.loading == DashboardLoading.PICKER && it.path != except }.forEach { page ->
            page.web.evaluateJavascript("window.__vacaCanParkCamera?.() === true") { canPark ->
                if (closed || pages[page.path] !== page || videoWanted(page.path)) return@evaluateJavascript
                // Detect iframe camera cards from their content, not their URL
                // names. Inline video/MJPEG pages still release their WebView.
                if (canPark != "true") { status("Prepared page cannot retain camera shell ${page.path}: $canPark"); discard(page.path) }
                else {
                    page.web.settings.offscreenPreRaster = false
                    status("Prepared camera shell retained ${page.path}")
                }
            }
        }
    }

    fun releaseUnselectedCamera() {
        status("Prepared camera memory trim")
        warmPaths = setOfNotNull(request ?: visible)
        warmUntil = 0L
        main.removeCallbacks(releaseWarmPages)
        setCameraActive()
        releasePickerPages(request ?: visible)
        pages.keys.filter { pages[it]?.loading == DashboardLoading.KEEP_ALIVE && OverlayLayout.isCameraView(it) && it != request && it != visible }.toList().forEach(::discard)
    }

    private fun ensure(path: String): Page {
        val layout = DevicePickerStore(context).layout(path)
        val loading = DevicePickerStore(context).loading(path)
        if (pages[path]?.loading?.let { it != loading } == true) discard(path)
        pages[path]?.let {
            it.web.settings.offscreenPreRaster = true
            if (it.layout.width != layout.width || it.layout.height != layout.height) {
                it.ready = false; it.stable = 0; it.generation++
                it.layout = layout
                parkPage(it)
            }
            it.layout = layout
            return it
        }
        val web = DashboardWebView(context) { message -> status("Prepared $path: $message") }
        val screen = size
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams((screen.widthPixels * layout.width).roundToInt(),
            (screen.heightPixels * layout.height).roundToInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = screen.widthPixels + 64 }
        val window = FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = 24 * resources.displayMetrics.density }
            clipToOutline = true
            addView(web, FrameLayout.LayoutParams(-1, -1))
        }
        wm.addView(window, params)
        val page = Page(path, web, layout, loading, window, params)
        page.streamEnabled = videoWanted(path)
        pages[path] = page
        web.settings.offscreenPreRaster = true
        web.addJavascriptInterface(object {
            @JavascriptInterface fun snapshot() = state
            @JavascriptInterface fun cameraActive() = videoWanted(path)
        }, "VacaPreparedPage")
        val script = PreparedPageScript.start(path) + if (loading == DashboardLoading.PICKER) "\n" + CameraPageScript.install else ""
        WebViewCompat.addDocumentStartJavaScript(web, script, setOf(config.haUrl))
        web.onNavigate = { DashboardChrome.apply(web) }
        web.onPageLoading = { page.ready = false; page.stable = 0; page.generation++ }
        web.onRendererGone = {
            if (pages[path] === page) {
                val wanted = request == path || visible == path
                if (wanted) { request = path; visible = null }
                discard(path)
                main.postDelayed({ if (!closed && handles(path) && (request == path || visible == path || loading == DashboardLoading.KEEP_ALIVE || pickerOpen)) ensure(path) }, 1000)
            }
        }
        web.onSignInRequired = { controller.onSignInRequired() }
        web.openDashboard(path)
        status("Preparing started $path (${loading.value})")
        return page
    }

    private fun check(page: Page) {
        if (page.loading == DashboardLoading.PICKER && !videoWanted(page.path)) {
            if (SystemClock.uptimeMillis() >= page.retryAt) {
                page.retryAt = SystemClock.uptimeMillis() + 30_000
                page.web.retryFailedStartup()
            }
            return
        }
        if (page.checking) return
        page.checking = true
        val checkedGeneration = page.generation
        page.web.evaluateJavascript(PreparedPageScript.readiness(page.path)) { result ->
            page.checking = false
            if (closed || pages[page.path] !== page || page.generation != checkedGeneration) return@evaluateJavascript
            if (result != "true") {
                page.ready = false; page.stable = 0
                // Hidden-but-attached pages must also recover HA startup failures.
                if (SystemClock.uptimeMillis() >= page.retryAt) {
                    page.retryAt = SystemClock.uptimeMillis() + 30_000
                    page.web.retryFailedStartup()
                }
                return@evaluateJavascript
            }
            if (page.ready) { if (request == page.path) present(page); return@evaluateJavascript }
            page.stable++
            if (page.stable < 2) return@evaluateJavascript
            val generation = page.generation
            page.web.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                override fun onComplete(id: Long) {
                    if (closed || pages[page.path] !== page || page.stable < 2 || page.generation != generation) return
                    if (!page.ready) status("Prepared dashboard ready ${page.path}")
                    page.ready = true
                    if (request == page.path) present(page)
                }
            })
        }
    }

    private fun present(page: Page) {
        if (request != page.path || !page.ready || closed) return
        request = null; visible = page.path
        warmUntil = SystemClock.uptimeMillis() + 15_000L
        main.removeCallbacks(releaseWarmPages)
        main.postDelayed(releaseWarmPages, 15_000L)
        reveal(page.path, page.web, page.layout)
        if (closed || visible != page.path || pages[page.path] !== page) return
        val screen = size
        page.params.x = (screen.widthPixels * page.layout.left()).roundToInt()
        page.params.y = (screen.heightPixels * page.layout.top()).roundToInt()
        // Move the existing window. Reparenting an already-decoding video to a
        // different window destroys its SurfaceView and produces a black frame.
        wm.updateViewLayout(page.window, page.params)
    }

    fun setPresentationAlpha(path: String, alpha: Float) {
        if (visible == path) pages[path]?.window?.alpha = alpha
    }

    fun parkController() { if (controller.parent == null) park(controller, OverlayLayout(.25f, .25f)) }

    private fun parkAll() {
        parkController()
        pages.values.forEach(::parkPage)
    }

    private fun parkPage(page: Page) {
        val screen = size
        page.params.x = screen.widthPixels + 64; page.params.y = 0
        page.params.width = (screen.widthPixels * page.layout.width).roundToInt().coerceAtLeast(1)
        page.params.height = (screen.heightPixels * page.layout.height).roundToInt().coerceAtLeast(1)
        wm.updateViewLayout(page.window, page.params)
        page.window.alpha = 1f
    }

    private fun park(web: WebView, layout: OverlayLayout) {
        val screen = size
        web.animate().withEndAction(null).cancel()
        if (web.parent !== parking) {
            (web.parent as? ViewGroup)?.removeView(web)
            parking.addView(web)
        }
        web.layoutParams = FrameLayout.LayoutParams((screen.widthPixels * layout.width).roundToInt().coerceAtLeast(1),
            (screen.heightPixels * layout.height).roundToInt().coerceAtLeast(1))
        web.alpha = 1f; web.visibility = View.VISIBLE
    }

    private fun discard(path: String) {
        pages.remove(path)?.let {
            wm.removeViewImmediate(it.window)
            it.window.removeView(it.web); it.web.destroy()
            status("Prepared dashboard released $path")
        }
    }

    override fun close() {
        closed = true; main.removeCallbacksAndMessages(null)
        pages.keys.toList().forEach(::discard)
        (controller.parent as? ViewGroup)?.removeView(controller)
        runCatching { wm.removeViewImmediate(parking) }
    }

    companion object {
        fun supported() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }
}
