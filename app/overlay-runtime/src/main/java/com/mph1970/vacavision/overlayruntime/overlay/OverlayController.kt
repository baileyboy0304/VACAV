// app/src/main/java/com/msp1974/vacompanion/overlay/OverlayController.kt
package com.mph1970.vacavision.overlayruntime.overlay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.mph1970.vacavision.overlayruntime.ble.BleRemoteService
import com.mph1970.vacavision.overlayruntime.picker.PickerController
import com.mph1970.vacavision.overlayruntime.utils.Logger
import java.lang.ref.WeakReference

class OverlayController private constructor(
    private val activity: Context,
    private val webView: WebView,
    private val host: OverlayHost?
) {
    private val log = Logger()
    private val main = Handler(Looper.getMainLooper())
    // HOME is consumed by Fire OS, so an overlay does not receive its KeyEvent.
    private val homeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS && intent.getStringExtra("reason") == "homekey") {
                log.i("Home: dismissing dashboard and picker")
                dismissToTv()
            }
        }
    }
    private fun watchHome() {
        androidx.core.content.ContextCompat.registerReceiver(activity, homeReceiver,
            android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }
    private var prepared: com.mph1970.vacavision.overlayruntime.web.PreparedDashboards? = null
    var onPathPresented: (String) -> Unit = {}
    var onRemoteTransport: (String) -> Unit = {}
    private var remotePath: String? = null
    private var remoteGeneration = 0
    private var backPending = false
    private fun endRemote(updateFocus: Boolean = true) {
        remotePath = null; remoteGeneration++; backPending = false
        host?.setDashboardRemote(false, updateFocus)
    }
    private fun remoteKey(action: String): Boolean {
        val path = remotePath ?: return false
        if (editorVisible || com.mph1970.vacavision.overlayruntime.overlay.AlarmOverlay.blocksPicker) return false
        log.i("Dashboard remote key: $action")
        if (action in listOf("play_pause", "play", "pause", "stop", "next", "previous")) {
            onRemoteTransport(action); return true
        }
        if (action !in listOf("left", "right", "up", "down", "select", "back")) return false
        val page = if (prepared?.handles(path) == true) prepared?.visibleWeb(path) else webView
        val generation = remoteGeneration
        if (action == "back") {
            if (backPending) return true
            backPending = true
            val fallback = Runnable {
                if (remotePath == path && remoteGeneration == generation && backPending) {
                    backPending = false
                    picker?.openFromUi(remote = true)
                }
            }
            main.postDelayed(fallback, 500)
            page?.evaluateJavascript(com.mph1970.vacavision.overlayruntime.web.DashboardRemoteScript.dispatch(path, action)) { result ->
                if (remoteGeneration == generation && backPending) {
                    main.removeCallbacks(fallback)
                    if (result == "true") backPending = false else fallback.run()
                }
            }
        } else page?.evaluateJavascript(com.mph1970.vacavision.overlayruntime.web.DashboardRemoteScript.dispatch(path, action), null)
        return true
    }
    val currentPath: String? get() = prepared?.activePath ?: webView.url?.let { OverlayLayout.path(it) }

    fun enablePreparedViews() {
        val dashboard = webView as? com.mph1970.vacavision.overlayruntime.web.DashboardWebView ?: return
        if (host == null || prepared != null) return
        if (!com.mph1970.vacavision.overlayruntime.web.PreparedDashboards.supported()) {
            log.w("Prepared dashboards unavailable: update the device WebView for document-start scripts")
            return
        }
        host.webViewContainer = null
        prepared = com.mph1970.vacavision.overlayruntime.web.PreparedDashboards(activity, dashboard, { log.i(it) }) { path, _, layout ->
            if (!editorVisible && installedFlag) {
                // The actual page keeps its own window/surface throughout preparation
                // and display. This proxy retains the host's existing fade/duration logic.
                val proxy = object : android.view.View(activity) {
                    override fun setAlpha(alpha: Float) {
                        super.setAlpha(alpha)
                        prepared?.setPresentationAlpha(path, alpha)
                    }
                }
                proxy.animate().setUpdateListener { prepared?.setPresentationAlpha(path, proxy.alpha) }
                host.webViewContainer = proxy
                host.showOverlay(layout)
                onPathPresented(path)
                log.i("Prepared dashboard revealed $path")
            }
        }
    }

    fun refreshPreparedViews() { prepared?.refresh() }
    fun releaseUnselectedCamera() { prepared?.releaseUnselectedCamera() }
    fun reloadDashboards() { webView.reload(); prepared?.reload() }

    @Volatile
    private var installedFlag: Boolean = true
    val isInstalled: Boolean
        get() = installedFlag

    // ---- Picker input gate ----
    private val ACTION_SYSTEM_READY = "com.mph1970.vacavision.overlayruntime.ACTION_SYSTEM_READY"
    private var systemReadySent = false
    private fun sendSystemReadyOnce() {
        if (systemReadySent) return
        systemReadySent = true
        LocalBroadcastManager.getInstance(activity)
            .sendBroadcast(Intent(ACTION_SYSTEM_READY))
        log.i("SystemReady: broadcast sent")
    }

    // --- Connect suppression ---
    private enum class ConnectPhase { CONNECTING, RUNNING }
    private var connectPhase: ConnectPhase = ConnectPhase.CONNECTING
    private var connectDefaultSeen = false
    private var connectClockSeen = false
    private val SUPPRESS_CONNECTING = true

    private fun resetConnectSuppression() {
        connectPhase = ConnectPhase.CONNECTING
        connectDefaultSeen = false
        connectClockSeen = false
        // Keep the clock connection colour independent of dashboard navigation.
        systemReadySent = false
        log.i("OverlayController: connect phase reset → CONNECTING")
    }

    // --- JS bridge + injection with debounce ---
    internal fun requestDashboardNavigation(href: String) {
        val origin = com.mph1970.vacavision.overlayruntime.settings.APPConfig.getInstance(activity).haUrl
        if (!com.mph1970.vacavision.overlayruntime.web.TrustedUrl.sameOrigin(origin, href)) return
        val path = OverlayLayout.path(href)
        if (OverlayLayout.viewName(path) in setOf("clock", "placeholder")) return
        if (host?.isPickerVisible() == true || editorVisible) return
        // A new HA route request may reopen a dismissed view; passive history
        // and page callbacks must continue to leave dismissed content hidden.
        if (suppressedNavigationPath == path) suppressedNavigationPath = null
        handlePossibleInAppNavigation(href, "ha-route-request")
    }

    private inner class NavBridge {
        @JavascriptInterface
        fun onNavigationRequest(href: String?) {
            if (!href.isNullOrBlank()) main.post { requestDashboardNavigation(href) }
        }

        @JavascriptInterface
        fun onPathChange(hrefRaw: String?) {
            if (hrefRaw.isNullOrBlank()) return
            log.d("JS NavBridge → $hrefRaw")
            main.post { handlePossibleInAppNavigation(hrefRaw, "js-nav-hook") }
        }
    }

    private var bootstrapTicker: Runnable? = null
    private fun startJsBootstrapTicker() {
        stopJsBootstrapTicker()
        var attempts = 0
        bootstrapTicker = object : Runnable {
            override fun run() {
                attempts++
                injectSpaNavigationHook()
                // also keep kiosk CSS in place in case HA re-renders
                forceHideHaChrome()
                main.postDelayed(this, if (attempts < 20) 500 else 5000)
            }
        }
        main.post(bootstrapTicker!!)
    }
    private fun stopJsBootstrapTicker() {
        bootstrapTicker?.let { main.removeCallbacks(it) }
        bootstrapTicker = null
    }

    private fun injectSpaNavigationHook() {
        val js = """
            (function() {
              try {
                if (window.__vaNavHooked) { window.__vaLastHref = location.href; return; }
                window.__vaNavHooked = true;
                window.__vaLastHref = location.href;
                window.__vaDebouncing = false;
                function debouncedNotify() {
                  if (window.__vaDebouncing) return;
                  window.__vaDebouncing = true;
                  setTimeout(function() {
                    try {
                      var href = location.href;
                      if (href !== window.__vaLastHref) {
                        window.__vaLastHref = href;
                        if (window.ViewAssistNav && ViewAssistNav.onPathChange) {
                          ViewAssistNav.onPathChange(href);
                        }
                      }
                    } catch (e) {}
                    window.__vaDebouncing = false;
                  }, 250);
                }
                var push = history.pushState;
                history.pushState = function() {
                  var requested = arguments[2];
                  var r = push.apply(this, arguments);
                  try {
                    if(requested != null && window.ViewAssistNav?.onNavigationRequest) {
                      ViewAssistNav.onNavigationRequest(new URL(requested, location.href).href);
                    }
                  } catch(e) {}
                  debouncedNotify(); return r;
                };
                var replace = history.replaceState;
                history.replaceState = function() { var r = replace.apply(this, arguments); debouncedNotify(); return r; };
                window.addEventListener('popstate', debouncedNotify);
                window.addEventListener('hashchange', debouncedNotify);
                if (!window.__vaHrefWatch) {
                  window.__vaHrefWatch = setInterval(function() {
                    try { if (location.href !== window.__vaLastHref) debouncedNotify(); } catch (e) {}
                  }, 500);
                }
                if (window.ViewAssistNav && ViewAssistNav.onPathChange) {
                  ViewAssistNav.onPathChange(location.href);
                }
              } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(ActiveDashboardNavigation.script(activeDashboardPath) + js, null)
    }

    // --- Keep HA header/sidebar hidden (force kiosk) ---
    private fun forceHideHaChrome() = com.mph1970.vacavision.overlayruntime.web.DashboardChrome.apply(webView)

    private fun forceHideHaChromeDelayed() {
        webView.post {
            forceHideHaChrome()
            main.postDelayed({ forceHideHaChrome() }, 300)
            main.postDelayed({ forceHideHaChrome() }, 1200)
        }
    }

    // Extra-aggressive timing when picker drives nav
    private fun forceHideHaChromeForPicker() {
        webView.post {
            forceHideHaChrome()
            main.postDelayed({ forceHideHaChrome() }, 100)
            main.postDelayed({ forceHideHaChrome() }, 300)
            main.postDelayed({ forceHideHaChrome() }, 600)
            main.postDelayed({ forceHideHaChrome() }, 1200)
            main.postDelayed({ forceHideHaChrome() }, 2000)
        }
    }

    /** (1) Public wrapper MainActivity can call without touching private members. */
    fun markOnlineAndSystemReady() {
        // Clock connection colour is controlled by the VACA service.
        sendSystemReadyOnce()
    }

    // --- Picker + BLE ---------------------------------------------------------------
    private var picker: PickerController? = null

    private fun startBleRemote() {
        try {
            activity.startService(Intent(activity, BleRemoteService::class.java))
            log.i("BleRemoteService started")
        } catch (e: Exception) {
            log.e("Failed to start BleRemoteService: ${e.message}")
        }
    }

    /** JS push to a dashboard path (used by picker select). Make this behave IDENTICAL to voice. */
    fun navigateToPath(path: String, remote: Boolean = false) {
        if (editorVisible) return
        if (!com.mph1970.vacavision.overlayruntime.web.TrustedUrl.isDashboardPath(path)) return
        endRemote()
        if (remote) { remotePath = OverlayLayout.path(path); host?.setDashboardRemote(true); log.i("Dashboard remote active: $remotePath") }
        prepared?.let { pool ->
            if (pool.handles(path)) {
                host?.webViewContainer = null
                host?.shrinkToBubble(notify = false)
                explicitPath = OverlayLayout.path(path)
                lastPresentedPath = explicitPath
                suppressedNavigationPath = null
                activeDashboardPath = null
                syncActiveDashboard()
                pool.show(path)
                if (remotePath != null) host?.setDashboardRemote(true)
                // This page receives View Assist commands; it must not also render
                // a second copy of the prepared dashboard in the background.
                webView.evaluateJavascript("""
                    if(location.pathname!=='/view-assist/clock'){
                      history.replaceState(null,'','/view-assist/clock');
                      window.dispatchEvent(new CustomEvent('location-changed'));
                    }
                """.trimIndent(), null)
                log.i("Picker navigation → $path (prepared)")
                return
            }
            host?.webViewContainer = null
            pool.dismiss()
            host?.webViewContainer = webView
        }
        explicitPath = OverlayLayout.path(path)
        suppressedNavigationPath = null
        lastPresentedPath = explicitPath
        activeDashboardPath = explicitPath?.takeUnless { OverlayLayout.viewName(it) in setOf("clock", "placeholder") }
        syncActiveDashboard()
        if (OverlayLayout.viewName(path) != "placeholder") {
            val layout = layoutFor(path)
            host?.applyOverlayBounds(layout)
            host?.showOverlay(layout)
        }

        // Apply kiosk chrome hiding before navigation
        forceHideHaChrome()

        val js = """
            (function(){
                try {
                    const target = ${org.json.JSONObject.quote(path)};
                    if (window.history && window.history.pushState) {
                        if (location.pathname !== target) {
                            history.pushState({}, '', target);
                        }
                        if (window.dispatchEvent) {
                            window.dispatchEvent(new CustomEvent('location-changed', {
                                detail: { pathname: target }
                            }));
                            window.dispatchEvent(new Event('popstate'));
                        }
                        if (window.ViewAssistNav && ViewAssistNav.onPathChange) {
                            ViewAssistNav.onPathChange(location.href);
                        }
                    } else {
                        location.href = target;
                    }
                } catch(e) { 
                    console.error('Navigation error:', e);
                    location.href = ${org.json.JSONObject.quote(path)}; 
                }
            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(js, null)
        }

        // Reinforce kiosk CSS
        main.postDelayed({ forceHideHaChrome() }, 150)
        main.postDelayed({ forceHideHaChrome() }, 300)
        main.postDelayed({ forceHideHaChrome() }, 600)

        log.i("Picker navigation → $path")
    }

    private var explicitPath: String? = null
    private var lastPresentedPath: String? = null
    private var suppressedNavigationPath: String? = null
    private var activeDashboardPath: String? = null
    private fun syncActiveDashboard() {
        // Initial collapse happens before openDashboard(). Do not initialise
        // Amazon WebView by injecting into its unattached about:blank document.
        if (webView.url.isNullOrBlank() || webView.url == "about:blank") return
        webView.evaluateJavascript(ActiveDashboardNavigation.script(activeDashboardPath), null)
    }

    // Page-finished/history/JS notifications are not new requests to show a
    // dashboard that the user has already dismissed or replaced with the picker.
    private fun suppressCurrentDashboard() {
        endRemote()
        suppressedNavigationPath = lastPresentedPath
        activeDashboardPath = null
        explicitPath = null
        syncActiveDashboard()
        if (prepared != null) {
            host?.webViewContainer = null
            prepared?.dismiss()
        }
    }
    private fun layoutFor(path: String): OverlayLayout = runCatching {
        com.mph1970.vacavision.overlayruntime.picker.DevicePickerStore(activity).layout(path)
    }.getOrElse { OverlayLayout.defaults(path) }

    private fun preSizeForPath(path: String) {
        if (!com.mph1970.vacavision.overlayruntime.web.TrustedUrl.isDashboardPath(path) || editorVisible) return
        if (prepared == null) host?.applyOverlayBounds(layoutFor(path))
    }

    fun hide() { endRemote(); host?.hideOverlay() }
    fun dismissToTv() {
        endRemote()
        picker?.dismissFromSystem()
        // Cancel pending preparation too: a late camera-ready callback must not
        // bring a dashboard back over the Fire TV launcher after HOME.
        suppressCurrentDashboard()
        host?.shrinkToBubble()
    }
    fun configureFade(fadeIn: Long, fadeOut: Long) { host?.setOverlayFadeDurations(fadeIn, fadeOut) }
    fun showPicker(remote: Boolean = false) { if (!editorVisible) picker?.openFromUi(remote) }
    fun togglePicker() { if (!editorVisible) picker?.toggleFromUi() }
    fun setConnectionOnline(online: Boolean) { host?.setBubbleOnline(online) }
    fun refreshClock() { host?.refreshClock() }
    // --- Public API ---
    fun destroy() {
        runCatching { activity.unregisterReceiver(homeReceiver) }
        endRemote()
        prepared?.close(); prepared = null
        stopJsBootstrapTicker()
        main.removeCallbacksAndMessages(null)
        webView.removeJavascriptInterface("ViewAssistNav")
        try { host?.destroy() } catch (_: Exception) {}
        picker?.dispose()
        picker = null
        installedFlag = false            // <-- add this line
        if (instance?.get() === this) instance = null
        log.i("OverlayController: destroyed")
    }


    companion object {
        fun dismissForHome() { instance?.get()?.dismissToTv() }
        var editorVisible: Boolean = false
            private set
        fun setEditorVisible(visible: Boolean) {
            editorVisible = visible
            instance?.get()?.let { controller ->
                if (visible) controller.endRemote()
                controller.picker?.setInputEnabled(!visible)
                controller.host?.setEditorHidden(visible)
                if (visible) controller.prepared?.dismiss() else controller.prepared?.refresh()
            }
        }
        // Singleton guard
        private var instance: WeakReference<OverlayController>? = null
        fun setPickerInputEnabled(enabled: Boolean) { instance?.get()?.picker?.setInputEnabled(enabled) }

        fun install(activity: Context, webView: WebView): OverlayController {
            instance?.get()?.let {
                it.log.w("OverlayController.install called again – reusing existing instance")
                return it
            }

            val host = OverlayHost.create(activity)
            val oc = OverlayController(activity, webView, host)
            instance = WeakReference(oc)
            oc.watchHome()

            host?.onCollapsed = { oc.suppressCurrentDashboard() }
            host?.onDashboardKey = { oc.remoteKey(it) }
            host?.onPickerShown = {
                oc.endRemote(updateFocus = false)
                // Keep an existing camera alive when its picker is opened again.
                oc.suppressedNavigationPath = oc.lastPresentedPath
                oc.activeDashboardPath = null; oc.explicitPath = null
                oc.syncActiveDashboard()
                if (oc.prepared != null) {
                    host.webViewContainer = null
                    oc.prepared?.pickerOpened()
                }
            }

            // Hand the WebView to the host container
            host?.webViewContainer = webView
            host?.shrinkToBubble()
            host?.setEditorHidden(editorVisible)

            // ✅ Expose NavBridge to JS so injectSpaNavigationHook() can call it
            webView.addJavascriptInterface(oc.NavBridge(), "ViewAssistNav")

            // Create picker (unchanged)
            if (host != null) {
                oc.picker = com.mph1970.vacavision.overlayruntime.picker.PickerController(
                    activity = activity,
                    host = host,
                    navigateTo = { path -> oc.navigateToPath(path) },
                    preSizeForPath = { path -> oc.preSizeForPath(path) },
                    preparesDashboards = { oc.prepared != null },
                    dashboardSelected = { path, remote -> oc.navigateToPath(path, remote) },
                    dashboardKey = { key -> oc.remoteKey(when(key) { "prev" -> "left"; "next" -> "right"; else -> key }) }
                )
            }

            // Fire “system ready” once picker receivers exist
            oc.sendSystemReadyOnce()

            // Start the repeated JS injection (already present)
            oc.startJsBootstrapTicker()

            // Start BLE remote (already present)
            // BLE is started explicitly after runtime permission has been granted.

            // Seed overlay state from the current URL (optional but helpful)
            oc.considerCurrentUrl(webView.url)

            return oc
        }
    }

    // --- Navigation handling --------------------------------------------------------
    private fun handlePossibleInAppNavigation(href: String, source: String) {
        if (editorVisible) return
        if (href.endsWith("/view-assist") || href.endsWith("/viewassist")) {
            resetConnectSuppression()
        }
        if (!href.contains("/viewassist/") && !href.contains("/view-assist/")) return

        val path = OverlayLayout.path(href)
        if (host?.isPickerVisible() == true) {
            log.d("Dashboard navigation ignored while picker is visible: $path")
            return
        }
        if (prepared != null) {
            if (OverlayLayout.viewName(path) == "clock" && prepared?.active == true) return
            if (prepared?.handles(path) == true) {
                if (path != prepared?.activePath && path != suppressedNavigationPath) navigateToPath(path)
                return
            }
            if (prepared?.active == true && OverlayLayout.viewName(path) != "placeholder") {
                navigateToPath(path)
                return
            }
        }
        if (OverlayLayout.viewName(path) == "clock" && activeDashboardPath != null && host?.isOverlayExpanded() == true) {
            log.i("Late clock navigation suppressed; keeping $activeDashboardPath ($source)")
            syncActiveDashboard()
            return
        }
        if (path == suppressedNavigationPath) {
            log.d("Notification for dismissed dashboard ignored: $path")
            return
        }
        // Multiple WebView/JS observers report the same navigation. Only a
        // new route or explicit navigateToPath request starts a new duration.
        if (path == lastPresentedPath && host?.isOverlayExpanded() == true) return
        val profile = OverlayLayout.viewName(href)
        if (explicitPath != OverlayLayout.path(href)) explicitPath = null

        // Ignore auto /clock while picker is visible
        if (profile == "clock" && (host?.isPickerVisible() == true)) {
            log.i("Clock nav ignored because picker is visible")
            return
        }

        if (profile == "clock" && explicitPath == null) {
            log.i("Clock nav ignored")
            host?.shrinkToBubble()
            return
        }

        if (!shouldShowForProfile(profile)) return

        log.d("Navigation detected ($source): $href → profile=$profile")
        lastPresentedPath = path
        suppressedNavigationPath = null
        activeDashboardPath = path.takeUnless { profile in setOf("clock", "placeholder") }
        syncActiveDashboard()
        if (prepared != null && host?.webViewContainer !== webView) {
            host?.webViewContainer = null
            prepared?.dismiss()
            host?.webViewContainer = webView
        }
        host?.showOverlay(layoutFor(href))

        // Apply chrome hiding for voice navigation (picker already applied it)
        webView.evaluateJavascript("window.__vaPickerNavigation || false") { result ->
            val isPickerNavigation = result == "true"
            webView.evaluateJavascript("window.__vaPickerNavigation = false", null)

            if (isPickerNavigation) {
                log.d("Picker navigation detected - chrome hiding already applied")
                forceHideHaChromeForPicker()
            } else {
                log.d("Voice navigation detected - applying standard chrome hiding")
                forceHideHaChromeDelayed()
            }
        }
    }

    private fun shouldShowForProfile(profile: String): Boolean {
        if (profile == "placeholder") {
            log.i("Overlay suppressed for placeholder")
            return false
        }
        if (explicitPath != null) return true

        if (connectPhase == ConnectPhase.CONNECTING) {
            when (profile) {
                "default" -> {
                    if (!connectDefaultSeen) {
                        connectDefaultSeen = true
                        log.i("*** CONNECTING WEBVIEW SCREEN *** default")
                        if (SUPPRESS_CONNECTING) {
                            log.i("*** CONNECTING overlay SUPPRESSED: default")
                            return false
                        }
                    }
                }
                "clock" -> {
                    if (!connectClockSeen) {
                        connectClockSeen = true
                        log.i("*** CONNECTING WEBVIEW SCREEN *** clock")
                        connectPhase = ConnectPhase.RUNNING
                        // Clock connection colour is controlled by the VACA service.
                        if (SUPPRESS_CONNECTING) {
                            log.i("*** CONNECTING overlay SUPPRESSED: clock")
                            host?.shrinkToBubble()
                            sendSystemReadyOnce()
                            return false
                        } else {
                            sendSystemReadyOnce()
                        }
                    } else {
                        return false
                    }
                }
            }
        }
        return true
    }

    /** Seed overlay state from the current URL if hooks haven't fired yet. */
    fun considerCurrentUrl(url: String?) {
        if (url.isNullOrBlank()) return
        handlePossibleInAppNavigation(url, source = "native-current")
    }
}


