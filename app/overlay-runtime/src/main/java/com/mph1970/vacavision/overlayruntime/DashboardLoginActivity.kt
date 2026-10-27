package com.mph1970.vacavision.overlayruntime

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.annotation.SuppressLint
import android.app.Activity
import android.os.*
import android.webkit.*
import android.widget.*
import com.mph1970.vacavision.overlayruntime.settings.APPConfig
import com.mph1970.vacavision.overlayruntime.web.TrustedUrl
import org.json.JSONObject
import org.json.JSONTokener
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Ordinary HA browser login; tokens never leave this app and the configured HA origin. */
class DashboardLoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var config: APPConfig
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient()
    private var validating = false
    private val capture = object : Runnable {
        override fun run() {
            if (TrustedUrl.sameOrigin(config.haUrl, web.url.orEmpty())) {
                web.evaluateJavascript("localStorage.getItem('hassTokens')") { raw ->
                    runCatching {
                        val json = JSONTokener(raw).nextValue() as? String ?: return@runCatching
                        val tokens = JSONObject(json)
                        val access = tokens.optString("access_token")
                        val refresh = tokens.optString("refresh_token")
                        if (access.isNotBlank() && refresh.isNotBlank() && !validating) validate(tokens)
                    }
                }
            }
            main.postDelayed(this, 1500)
        }
    }
    private fun validate(tokens: JSONObject) {
        validating = true
        val origin = config.haUrl
        val clientId = tokens.optString("clientId", "$origin/")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val body = FormBody.Builder().add("grant_type", "refresh_token")
                        .add("refresh_token", tokens.getString("refresh_token")).add("client_id", clientId).build()
                    http.newCall(Request.Builder().url("$origin/auth/token").post(body).build()).execute().use {
                        it.code to it.body?.string().orEmpty()
                    }
                }
                if (origin != config.haUrl || !TrustedUrl.sameOrigin(origin, web.url.orEmpty())) return@launch
                if (result.first in listOf(400, 401)) {
                    // A revoked token left in WebView storage must not cause a sign-in loop.
                    web.evaluateJavascript("localStorage.removeItem('hassTokens')") { web.loadUrl(origin) }
                } else if (result.first == 200) {
                    val access = JSONObject(result.second).getString("access_token")
                    config.accessToken = access; config.refreshToken = tokens.getString("refresh_token")
                    config.prefs.edit().putString("auth_client_id", clientId).apply()
                    getSystemService(android.app.NotificationManager::class.java).cancel(101)
                    finish()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            } finally { validating = false }
        }
    }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        config = APPConfig.getInstance(this)
        if (!TrustedUrl.validOrigin(config.haUrl)) { finish(); return }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !TrustedUrl.sameOrigin(config.haUrl, request.url.toString())
            }
        }
        setContentView(web)
        web.loadUrl(config.haUrl)
        main.post(capture)
    }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); scope.cancel(); http.dispatcher.cancelAll(); if (::web.isInitialized) web.destroy(); super.onDestroy() }
}
