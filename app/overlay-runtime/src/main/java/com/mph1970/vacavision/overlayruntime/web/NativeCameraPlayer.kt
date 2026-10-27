package com.mph1970.vacavision.overlayruntime.web

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.graphics.Color
import android.graphics.Matrix
import android.view.TextureView
import android.view.ViewGroup
import android.view.Gravity
import android.view.Display
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.roundToInt

/** Render video beside WebView in its rounded host, not in AWV's private child hierarchy. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class NativeCameraPlayer(private val web: WebView, private val trusted: () -> Boolean,
                                  private val status: (String) -> Unit) {
    init {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, OzolioCameraScript.script, setOf(OzolioCameraScript.ORIGIN))
        }
    }
    private var generation = 0
    private var attached = false
    private var scanning = false
    private var id: Int? = null
    private var player: ExoPlayer? = null
    private var panel: FrameLayout? = null
    private var texture: TextureView? = null
    private var videoSize = VideoSize.UNKNOWN
    private var fit = "contain"
    private fun canRender() = attached && web.display?.state == Display.STATE_ON
    private val timeout = Runnable { if (canRender()) fail("startup timed out") else suspend() }
    private val poll = object : Runnable {
        override fun run() {
            if (!attached) return
            if (!canRender()) suspend()
            else if (trusted() && !scanning) {
                scanning = true
                val epoch = generation
                web.evaluateJavascript(NativeCameraScript.probe) { value ->
                    if (epoch != generation || !attached) return@evaluateJavascript
                    scanning = false
                    if (!canRender()) { suspend(); return@evaluateJavascript }
                    val payload = runCatching { (JSONTokener(value).nextValue() as? String)?.let { JSONObject(it) } }.getOrNull()
                    if (payload == null) release(false) else update(payload)
                }
            } else if (!trusted()) release(false)
            web.postDelayed(this, if (id == null) 1000 else 250)
        }
    }

    fun attach() { attached = true; web.removeCallbacks(poll); web.post(poll) }
    fun detach() { attached = false; generation++; scanning = false; web.removeCallbacks(poll); release(true) }
    fun navigation() { generation++; scanning = false; release(false) }

    private fun update(data: JSONObject) {
        val next = data.optInt("id", -1)
        val source = data.optString("source")
        if (next < 0 || !validSource(source)) { release(true); return }
        if (id == next) { geometry(data); return }
        release(true)
        id = next
        val epoch = generation
        web.evaluateJavascript(NativeCameraScript.claim(next)) { result ->
            if (!attached || epoch != generation || id != next) return@evaluateJavascript
            if (!canRender()) { suspend(); return@evaluateJavascript }
            if (result != "true") { release(false); return@evaluateJavascript }
            try {
                val holder = FrameLayout(web.context).apply {
                    setBackgroundColor(Color.BLACK)
                    isClickable = false; isFocusable = false
                }
                val output = TextureView(web.context).apply { isOpaque = false; isFocusable = false }
                holder.addView(output, FrameLayout.LayoutParams(-1, -1))
                panel = holder; texture = output
                val host = web.parent as? FrameLayout ?: error("Camera requires a FrameLayout host")
                host.addView(holder, host.indexOfChild(web) + 1, FrameLayout.LayoutParams(1, 1))
                // TextureView creates its output surface when drawn. Alpha 0
                // can suppress that first draw on Fire OS and deadlock startup.
                // PreparedDashboards gates the whole window until first frame.
                holder.alpha = 1f
                geometry(data)
                // No HA token/cookie forwarding. Public camera streams only; failures return to WebView.
                val http = DefaultHttpDataSource.Factory().setUserAgent(web.settings.userAgentString)
                    .setConnectTimeoutMs(8000).setReadTimeoutMs(8000)
                val camera = ExoPlayer.Builder(web.context)
                    .setLoadControl(DefaultLoadControl.Builder()
                        .setBufferDurationsMs(2500, 10000, 500, 1000)
                        .setTargetBufferBytes(12 * 1024 * 1024)
                        .setPrioritizeTimeOverSizeThresholds(false).build())
                    .setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
                player = camera
                camera.volume = 0f
                camera.repeatMode = if (data.optBoolean("loop")) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                camera.setVideoTextureView(output)
                camera.addListener(object : Player.Listener {
                    private var rendered = false
                    override fun onVideoSizeChanged(size: VideoSize) { if (player === camera) { videoSize = size; transform() } }
                    override fun onRenderedFirstFrame() {
                        if (player !== camera || rendered) return
                        rendered = true
                        web.removeCallbacks(timeout)
                        holder.alpha = 1f
                        web.evaluateJavascript(NativeCameraScript.finish(next, true), null)
                        status("Camera video ready in rounded TextureView")
                    }
                    override fun onPlayerError(error: PlaybackException) { if (player === camera) fail(error.errorCodeName) }
                })
                camera.setMediaItem(MediaItem.fromUri(source))
                camera.prepare(); camera.playWhenReady = true
                web.postDelayed(timeout, 15000)
                status("Camera video preparing in TextureView")
            } catch (e: Exception) { fail(e.javaClass.simpleName) }
        }
    }

    private fun geometry(data: JSONObject) {
        val holder = panel ?: return
        val r = data.optJSONObject("rect") ?: return
        val viewport = data.optDouble("viewport", 0.0)
        if (!viewport.isFinite() || viewport <= 0 || web.width <= 0) return
        val scale = web.width / viewport
        val x = r.optDouble("x", 0.0); val y = r.optDouble("y", 0.0)
        val w = r.optDouble("width", 0.0); val h = r.optDouble("height", 0.0)
        if (!listOf(x,y,w,h).all(Double::isFinite) || w <= 0 || h <= 0) return
        val px = web.left + (x * scale + web.translationX).roundToInt()
        val py = web.top + (y * scale + web.translationY).roundToInt()
        val pw = (w * scale).roundToInt().coerceIn(1, web.width * 4)
        val ph = (h * scale).roundToInt().coerceIn(1, web.height.coerceAtLeast(1) * 4)
        val old = holder.layoutParams as FrameLayout.LayoutParams
        if (old.leftMargin != px || old.topMargin != py || old.width != pw || old.height != ph) {
            holder.layoutParams = FrameLayout.LayoutParams(pw, ph, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = px; topMargin = py
            }
        }
        fit = data.optString("fit", "contain")
        transform()
    }

    private fun transform() {
        val view = texture ?: return
        val width = panel?.layoutParams?.width?.toFloat() ?: return
        val height = panel?.layoutParams?.height?.toFloat() ?: return
        val vw = videoSize.width * videoSize.pixelWidthHeightRatio
        val vh = videoSize.height.toFloat()
        if (width <= 0 || height <= 0 || vw <= 0 || vh <= 0) return
        val matrix = Matrix()
        if (fit != "fill") {
            val scale = if (fit == "cover") maxOf(width/vw,height/vh) else minOf(width/vw,height/vh)
            matrix.setScale(vw*scale/width, vh*scale/height, width/2, height/2)
        }
        view.setTransform(matrix)
    }

    private fun fail(reason: String) { status("Native camera returned to browser: $reason"); release(true, true) }
    private fun suspend() {
        id?.takeIf { trusted() }?.let { web.evaluateJavascript(NativeCameraScript.suspend(it), null) }
        release(false)
    }
    private fun release(restore: Boolean, block: Boolean = false) {
        web.removeCallbacks(timeout)
        val previous = id; id = null
        player?.release(); player = null
        panel?.let { (it.parent as? ViewGroup)?.removeView(it) }; panel = null; texture = null; videoSize = VideoSize.UNKNOWN
        if (restore && previous != null && trusted()) web.evaluateJavascript(NativeCameraScript.finish(previous, false, block), null)
    }

    companion object {
        fun validSource(source: String): Boolean = runCatching {
            val uri = java.net.URI(source)
            uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                (uri.path.endsWith(".m3u8", true) || uri.path.endsWith(".mp4", true))
        }.getOrDefault(false)
    }
}
