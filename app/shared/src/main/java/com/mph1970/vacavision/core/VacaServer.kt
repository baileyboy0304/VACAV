package com.mph1970.vacavision.core

import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One active HA connection, plus short-lived discovery/diagnostic connections. */
class VacaServer(
    private val info: () -> JSONObject,
    private val capabilities: () -> JSONObject,
    private val onSettings: (JSONObject, String) -> Unit,
    private val onAction: (String, JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val allowedHost: () -> String = { "" },
) : AutoCloseable {
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val executor = Executors.newCachedThreadPool()
    private val updates = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var active: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    fun start(port: Int = VacaProtocol.PORT) {
        check(!running)
        server = ServerSocket(port).apply { reuseAddress = true }
        running = true
        executor.execute {
            while (running) {
                try {
                    val socket = server!!.accept()
                    if (clients.size >= 8) { socket.close(); continue }
                    socket.soTimeout = 15000
                    socket.tcpNoDelay = true
                    clients.add(socket)
                    executor.execute { serve(socket) }
                } catch (e: Exception) { if (running) onStatus("VACA listener error: ${e.javaClass.simpleName}") }
            }
        }
        timer.scheduleAtFixedRate({ active?.let { send(it, WireEvent("ping")) } }, 3, 3, TimeUnit.SECONDS)
    }
    val port: Int get() = server?.localPort ?: 0
    private fun send(socket: Socket, event: WireEvent) {
        try { synchronized(socket) { WyomingWire.write(socket.getOutputStream(), event) } }
        catch (_: Exception) { runCatching { socket.close() } }
    }
    // WebView and player callbacks arrive on Android's main thread. Never write sockets there.
    fun publish(event: WireEvent) {
        val socket = active ?: return
        if (running) runCatching { updates.execute { if (active === socket) send(socket, event) } }
    }
    private fun serve(socket: Socket) {
        try {
            val input = socket.getInputStream().buffered()
            while (running) {
                val event = WyomingWire.read(input) ?: break
                when (event.type) {
                    "describe" -> send(socket, WireEvent("info", info()))
                    "capabilities" -> send(socket, WireEvent("capabilities", capabilities()))
                    "ping" -> send(socket, WireEvent("pong"))
                    "pong" -> Unit
                    "run-vision" -> {
                        if (event.data.optString("protocol") != VacaProtocol.IDENTITY ||
                            event.data.optInt("protocol_version") != VacaProtocol.PROTOCOL_VERSION) {
                            send(socket, WireEvent("error", JSONObject().put("code", "wrong-device-type")))
                            break
                        }
                        val expected = allowedHost()
                        if (expected.isNotBlank() && java.net.InetAddress.getAllByName(expected).none { it == socket.inetAddress }) {
                            send(socket, WireEvent("error", JSONObject().put("text", "HA host does not match configured server")))
                            break
                        }
                        active?.takeIf { it !== socket }?.close()
                        active = socket
                        onStatus("Home Assistant connected")
                        send(socket, WireEvent("custom-event", JSONObject().put("event_type", "settings")))
                    }
                    "pause-vision" -> if (active === socket) { active = null; onStatus("Home Assistant paused") }
                    "run-satellite", "run-overlay" -> {
                        send(socket, WireEvent("error", JSONObject().put("code", "wrong-device-type")
                            .put("text", "Use VACA_Vision")))
                        break
                    }
                    "custom-event" -> if (active === socket) {
                        val body = VacaProtocol.body(event.data)
                        when (event.data.optString("event_type")) {
                            "settings" -> onSettings(body.optJSONObject("settings") ?: JSONObject(), socket.inetAddress.hostAddress!!)
                            "action" -> onAction(body.optString("action"), body.optJSONObject("payload") ?: JSONObject())
                        }
                    }
                    "audio-start", "transcribe", "synthesize" -> send(socket, WireEvent("error", JSONObject().put("code", "unsupported").put("text", "VACA_Vision does not own audio")))
                }
            }
        } catch (e: Exception) {
            if (running && active === socket) onStatus("VACA connection ended: ${e.javaClass.simpleName}: ${e.message}")
        }
        finally {
            clients.remove(socket)
            if (active === socket) { active = null; onStatus("Waiting for Home Assistant") }
            runCatching { socket.close() }
        }
    }
    override fun close() {
        running = false
        active = null
        runCatching { server?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear(); timer.shutdownNow(); updates.shutdownNow(); executor.shutdownNow()
    }
}
