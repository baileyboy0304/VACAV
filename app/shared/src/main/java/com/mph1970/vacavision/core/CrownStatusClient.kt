package com.mph1970.vacavision.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Read-only loopback adapter. No shell, microphone, music player, or WebView bridge. */
class CrownStatusClient(private val changed: (JSONObject) -> Unit) : AutoCloseable {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null
    private var retrySeconds = 1L
    fun start() { worker.execute { poll() } }
    private fun poll() {
        if (closed) return
        val status = try {
            val http = URL("http://127.0.0.1:16701/status").openConnection() as HttpURLConnection
            connection = http
            try {
                http.connectTimeout = 1500; http.readTimeout = 1500
                http.instanceFollowRedirects = false
                check(http.responseCode == 200)
                val raw = http.inputStream.use { input ->
                    val bytes = ByteArray(8193)
                    var count = 0
                    while (count < bytes.size) {
                        val n = input.read(bytes, count, bytes.size - count)
                        if (n < 0) break
                        count += n
                    }
                    check(count <= 8192) { "Crown status too large" }
                    String(bytes, 0, count, Charsets.UTF_8)
                }
                normalise(JSONObject(raw)).also { retrySeconds = 1 }
            } finally { http.disconnect(); connection = null }
        } catch (_: Exception) {
            retrySeconds = (retrySeconds * 2).coerceAtMost(30)
            offline()
        }
        if (!closed) {
            changed(status)
            runCatching { worker.schedule({ poll() }, retrySeconds, TimeUnit.SECONDS) }
        }
    }
    override fun close() {
        closed = true; connection?.disconnect(); worker.shutdownNow()
    }
    companion object {
        fun offline() = JSONObject().put("available", false).put("connected", false).put("state", "OFFLINE").put("muted", JSONObject.NULL)
        fun normalise(raw: JSONObject): JSONObject {
            val connected = raw.getBoolean("connected")
            val muted = raw.getBoolean("muted")
            val state = raw.getString("state")
            require(state in setOf("IDLE", "LISTENING", "THINKING", "SPEAKING", "MUTED", "OFFLINE"))
            return JSONObject().put("available", true).put("connected", connected)
                .put("state", if (muted) "MUTED" else if (!connected) "OFFLINE" else state).put("muted", muted)
        }
    }
}
