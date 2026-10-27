package com.mph1970.vacavision.overlayruntime.web

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.*
import com.mph1970.vacavision.overlayruntime.settings.APPConfig
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
class DashboardWebView(context: Context, private val status: (String) -> Unit) : WebView(context) {
    private val config = APPConfig.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient()
    private var documentGeneration = 0
    private var presentation = 0
    private var connectionRecoveryUsed = false
    private var connectionReported = false
    private val nativeCamera by lazy { NativeCameraPlayer(this,
        { TrustedUrl.sameOrigin(config.haUrl, url.orEmpty()) }, status) }
    private val connectionCheck = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            if (isShown && TrustedUrl.sameOrigin(config.haUrl, url.orEmpty())) {
                evaluateJavascript(DashboardConnectionRecovery.script(documentGeneration, presentation), null)
            }
            postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presentation++
        connectionRecoveryUsed = false
        connectionReported = false
        removeCallbacks(connectionCheck)
        post(connectionCheck)
        nativeCamera.attach()
    }

    override fun onDetachedFromWindow() {
        nativeCamera.detach()
        removeCallbacks(connectionCheck)
        super.onDetachedFromWindow()
    }
    var onNavigate: (String) -> Unit = {}
    var onPageLoading: () -> Unit = {}
    var onRendererGone: () -> Unit = {}
    var onSignInRequired: () -> Unit = {}
    var onClockTimeZoneChanged: () -> Unit = {}
    private var editorPath: String? = null
    private val editorGuard = object : Runnable {
        override fun run() {
            if (editorPath == null) return
            enforceEditorPath()
            postDelayed(this, 500)
        }
    }
    /** View Assist registers this browser and navigates home; an editor owns its route instead. */
    fun openEditorDashboard(path: String) {
        require(TrustedUrl.isDashboardPath(path))
        val loaded = editorPath != null && TrustedUrl.sameOrigin(config.haUrl, url.orEmpty())
        editorPath = path
        removeCallbacks(editorGuard)
        if (loaded) enforceEditorPath() else openDashboard(path)
        post(editorGuard)
    }
    private fun enforceEditorPath() {
        val target = editorPath ?: return
        if (!TrustedUrl.sameOrigin(config.haUrl, url.orEmpty())) return
        evaluateJavascript(editorNavigationScript(target), null)
    }
    private class SignInExpired : Exception("Home Assistant sign-in expired")
    init {
        // Register origin-restricted camera adapters before the first document/iframe loads.
        nativeCamera
        setBackgroundColor(Color.TRANSPARENT)
        settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true; loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)
        }
        addJavascriptInterface(object {
            @JavascriptInterface fun connectionResult(generation: Int, connected: Boolean) {
                post { handleConnectionResult(generation, connected) }
            }
        }, "VacaDashboardConnection")
        addJavascriptInterface(object {
            @JavascriptInterface fun getViewAssistCAUUID() = config.uuid
            @JavascriptInterface fun sendEvent(event: String, data: String) {
                if (event == "location-changed") post { url?.let(onNavigate) }
            }
        }, "ViewAssistApp")
        addJavascriptInterface(object {
            @JavascriptInterface fun getExternalAuth(payload: String) { post { authorize() } }
            @JavascriptInterface fun revokeExternalAuth(payload: String) {
                post { config.accessToken = ""; config.refreshToken = ""; status("Home Assistant sign-in required") }
            }
        }, "externalApp")
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                nativeCamera.navigation()
                documentGeneration++
                onPageLoading()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !TrustedUrl.sameOrigin(config.haUrl, request.url.toString())
            override fun onPageFinished(view: WebView, url: String) {
                if (!TrustedUrl.sameOrigin(config.haUrl, url)) return
                evaluateJavascript(TRANSPARENCY_SCRIPT, null)
                enforceEditorPath()
                onNavigate(url)
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { onNavigate(url) }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) status("Dashboard load failed: ${error.description}")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                status("Dashboard renderer restarted")
                post { onRendererGone() }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
    }
    fun openDashboard(dashboardPath: String = config.dashboard) {
        if (!TrustedUrl.validOrigin(config.haUrl)) return
        val path = dashboardPath.takeIf(TrustedUrl::isDashboardPath) ?: "/view-assist/clock"
        val sep = if (path.contains('?')) "&" else "?"
        loadUrl(config.haUrl + path + sep + "external_auth=1")
    }
    private fun authorize() {
        scope.launch {
            if (!TrustedUrl.sameOrigin(config.haUrl, url.orEmpty())) return@launch
            val origin = config.haUrl
            var expiry = 31536000
            try {
                if (config.refreshToken.isNotBlank()) {
                    val result = withContext(Dispatchers.IO) {
                        val body = FormBody.Builder().add("grant_type", "refresh_token")
                            .add("refresh_token", config.refreshToken)
                            .add("client_id", config.prefs.getString("auth_client_id", origin + "/")!!).build()
                        http.newCall(Request.Builder().url("$origin/auth/token").post(body).build()).execute().use {
                            if (it.code in listOf(400, 401)) throw SignInExpired()
                            check(it.isSuccessful) { "Home Assistant token refresh failed (${it.code})" }
                            JSONObject(it.body!!.string())
                        }
                    }
                    config.accessToken = result.getString("access_token")
                    expiry = result.optInt("expires_in", 1800)
                } else if (config.accessToken.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        http.newCall(Request.Builder().url("$origin/api/").header("Authorization", "Bearer ${config.accessToken}").build()).execute().use {
                            if (it.code == 401) throw SignInExpired()
                            check(it.isSuccessful) { "Home Assistant is unavailable (${it.code})" }
                        }
                    }
                }
                check(config.accessToken.isNotBlank()) { "Open settings and sign in or supply a long-lived access token" }
                if (!TrustedUrl.sameOrigin(origin, url.orEmpty()) || origin != config.haUrl) return@launch
                val token = JSONObject().put("access_token", config.accessToken).put("expires_in", expiry)
                enforceEditorPath()
                evaluateJavascript("window.externalAuthSetToken(true, $token);", null)
                status("Dashboard token supplied")
                refreshClockTimeZone(origin, config.accessToken)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is SignInExpired) {
                    config.accessToken = ""; config.refreshToken = ""
                    onSignInRequired()
                }
                evaluateJavascript("window.externalAuthSetToken(false);", null)
                status(e.message ?: "Dashboard authentication failed")
            }
        }
    }
    private fun refreshClockTimeZone(origin: String, accessToken: String) {
        scope.launch {
            try {
                val zoneId = withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url("$origin/api/config")
                        .header("Authorization", "Bearer $accessToken").build()).execute().use {
                        check(it.isSuccessful) { "HA configuration unavailable (${it.code})" }
                        JSONObject(it.body!!.string()).optString("time_zone")
                    }
                }
                if (com.mph1970.vacavision.overlayruntime.overlay.HaClock(config).rememberTimeZone(origin, zoneId)) {
                    onClockTimeZoneChanged()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.w("VACA_Vision", "Clock timezone refresh failed; retaining cached timezone", e)
            }
        }
    }
    internal fun handleConnectionResult(generation: Int, connected: Boolean) {
        // A detached page can finish authentication after its overlay has expired.
        // Only recover the current, visible document, once per presentation.
        if (generation != documentGeneration || !isAttachedToWindow || !isShown ||
            !TrustedUrl.sameOrigin(config.haUrl, url.orEmpty())) return
        if (connected) {
            if (!connectionReported) status("Dashboard connected")
            connectionReported = true
        } else if (!connectionRecoveryUsed) {
            connectionRecoveryUsed = true
            status("Dashboard startup connection failed; retrying visible dashboard")
            reload()
        }
    }
    /** Background preparation retries are paced by the pool, never by a JS reload loop. */
    fun retryFailedStartup() {
        connectionRecoveryUsed = false
        presentation++
        if (isAttachedToWindow && TrustedUrl.sameOrigin(config.haUrl, url.orEmpty()))
            evaluateJavascript(DashboardConnectionRecovery.script(documentGeneration, presentation), null)
    }
    override fun destroy() { nativeCamera.detach(); editorPath = null; removeCallbacks(connectionCheck); removeCallbacks(editorGuard); scope.cancel(); http.dispatcher.cancelAll(); super.destroy() }
    companion object {
        fun editorNavigationScript(path: String) = """
            (function(){
              window.__vacaEditorTarget=${JSONObject.quote(path)};
              if(!window.__vacaEditorHistory){
                var push=history.pushState.bind(history), replace=history.replaceState.bind(history);
                window.__vacaEditorHistory={push:push,replace:replace};
                function route(value){
                  if(value==null)return value;
                  var u=new URL(value,location.href);
                  return u.origin===location.origin && /^\/view-?assist(?:\/|$)/.test(u.pathname)
                    ? window.__vacaEditorTarget : value;
                }
                history.pushState=function(s,t,u){return push(s,t,route(u));};
                history.replaceState=function(s,t,u){return replace(s,t,route(u));};
              }
              var target=new URL(window.__vacaEditorTarget,location.href);
              if(/^\/view-?assist(?:\/|$)/.test(location.pathname) && location.pathname!==target.pathname){
                window.__vacaEditorHistory.push(null,'',window.__vacaEditorTarget);
                window.dispatchEvent(new CustomEvent('location-changed'));
              }
            })();
        """.trimIndent()
        // Follow HA shadow roots. Only page surfaces are transparent; cards keep their styling.
        val TRANSPARENCNCY_STYLE = "html,body,home-assistant,home-assistant-main,ha-panel-lovelace,hui-root,hui-view,hui-masonry-view,hui-sections-view,#view { background:transparent!important; background-color:transparent!important; --primary-background-color:transparent!important; --lovelace-background:transparent!important; }"
        val TRANSPARENCY_SCRIPT = """
            (function(){
              function visit(root){
                if(!root.querySelector) return;
                if(!root.querySelector('#vaca-overlay-transparent')) {
                  var s=document.createElement('style'); s.id='vaca-overlay-transparent';
                  s.textContent=${JSONObject.quote(TRANSPARENCNCY_STYLE)}; root.appendChild(s);
                }
                root.querySelectorAll('*').forEach(function(e){if(e.shadowRoot) visit(e.shadowRoot);});
              }
              if(!window.__vacaTransparency) { window.__vacaTransparency=setInterval(function(){visit(document.head); document.querySelectorAll('*').forEach(function(e){if(e.shadowRoot)visit(e.shadowRoot);});},2000); }
              visit(document.head);
            })();
        """.trimIndent()
    }
}
