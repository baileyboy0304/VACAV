package com.mph1970.vacavision.overlayruntime.picker

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import com.mph1970.vacavision.overlayruntime.web.TrustedUrl
import okhttp3.*
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** One bounded, authenticated read. Must run on a worker, never the TV UI thread. */
class HaPickerDiscovery : Closeable {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private val result = CompletableFuture<List<DiscoveredView>>()

    fun fetch(origin: String, accessToken: String, refreshToken: String, clientId: String): List<DiscoveredView> {
        require(TrustedUrl.validOrigin(origin))
        check(!closed)
        val access = if (refreshToken.isNotBlank()) {
            val request = Request.Builder().url("$origin/auth/token").post(FormBody.Builder()
                .add("grant_type", "refresh_token").add("refresh_token", refreshToken)
                .add("client_id", clientId).build()).build()
            client.newCall(request).execute().use { response ->
                if (response.code in listOf(400, 401, 403)) error("Sign in to Home Assistant, then populate again.")
                check(response.isSuccessful) { "Home Assistant token refresh failed. Picker unchanged." }
                JSONObject(response.body?.string() ?: "").getString("access_token")
            }
        } else accessToken
        check(access.isNotBlank()) { "Sign in to Home Assistant, then populate again." }
        check(!closed)
        val url = origin.replaceFirst("http", "ws") + "/api/websocket"
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", access).toString())
                        "auth_ok" -> webSocket.send(JSONObject().put("id", 1).put("type", "lovelace/config")
                            .put("url_path", "view-assist").put("force", true).toString())
                        "auth_invalid" -> result.completeExceptionally(IOException("Sign in to Home Assistant, then populate again."))
                        "result" -> if (message.optInt("id") == 1) {
                            check(message.optBoolean("success")) { "Cannot read /view-assist. Check that the dashboard exists and this HA user can access it. Picker unchanged." }
                            result.complete(DashboardViews.parse(message.getJSONObject("result")))
                        }
                    }
                } catch (error: Exception) { result.completeExceptionally(error) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.completeExceptionally(IOException("Cannot reach Home Assistant. Picker unchanged."))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                result.completeExceptionally(IOException("Home Assistant disconnected. Picker unchanged."))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                result.completeExceptionally(IOException("Home Assistant disconnected. Picker unchanged."))
            }
        })
        if (closed) socket?.cancel()
        try { return result.get(20, TimeUnit.SECONDS) }
        catch (error: java.util.concurrent.ExecutionException) { throw error.cause ?: error }
        catch (_: java.util.concurrent.TimeoutException) { error("Home Assistant timed out. Picker unchanged.") }
        finally { socket?.close(1000, "picker complete") }
    }

    override fun close() {
        closed = true
        result.cancel(true)
        socket?.cancel()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
