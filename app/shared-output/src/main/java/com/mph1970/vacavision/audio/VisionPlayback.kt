package com.mph1970.vacavision.audio

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.mph1970.vacavision.core.ServiceAdvertisement
import com.sendspin.protocol.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Output only. MSP1974/Dinki attribution retained; Sendspin SDK and renderer from supplied V13Overlay. */
class VisionPlayback(private val context: Context, private val uuid: String, name: String, private val report: (JSONObject) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("vision_playback", Context.MODE_PRIVATE)
    private val advertisement = ServiceAdvertisement(context) {}
    private val http = OkHttpClient()
    private val moshi = Moshi.Builder().add(JsonOptionalAdapterFactory()).addLast(KotlinJsonAdapterFactory()).build()
    @Volatile private var attenuation = 1f
    private var leaseUntil = 0L
    private var session = ""
    private var player: ExoPlayer? = null
    private var closed = false
    private var replyStarted = false
    private var replyRequested = false
    private var replyVolume = prefs.getFloat("reply_volume", 0.7f)
    private val store = object : ClientSettingsStore {
        override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
        override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
        override fun getString(key: String, default: String?) = prefs.getString(key, default)
        override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    }
    private val client = SendSpinClient(
        okHttpClient = http, moshi = moshi,
        preferences = ClientPreferences(
            playerBufferCapacity = AudioBufferSizing.NETWORK_BYTES,
            supportedFormats = listOf(AudioFormat("pcm", 2, 48000, 16), AudioFormat("pcm", 2, 44100, 16), AudioFormat("pcm", 1, 48000, 16)),
            artworkChannels = emptyList(),
            supportedOptionalRoles = setOf(OptionalRole.PLAYER, OptionalRole.METADATA, OptionalRole.CONTROLLER)),
        clientId = "vaca-vision-$uuid", clientName = "$name Sendspin",
        manufacturer = "VACA_Vision", productName = "Display and Dot", softwareVersion = "0.2.0",
        audioPlayerFactory = { buffer, clock -> SendspinAudioPlayer(buffer, clock,
            { playing -> report(JSONObject().put("music_playing", playing)) },
            { report(JSONObject().put("music_error", true)) }, duckGain = { attenuation }) },
        settingsStore = store)
    private val host = SendSpinServerHost(client, moshi, { prefs.getString("last_server", "")!! }, port = 0, scope = scope)
    private val watchdog = object : Runnable {
        override fun run() {
            if (closed) return
            if (session.isNotEmpty() && SystemClock.elapsedRealtime() >= leaseUntil) finish("expired")
            main.postDelayed(this, 1000)
        }
    }
    init {
        client.setRequiredLeadTimeMs(AudioBufferSizing.LEAD_TIME_MS)
        client.setMinBufferMs(AudioBufferSizing.LEAD_TIME_MS)
        host.startServer()
        scope.launch {
            try {
                withTimeout(10000) { host.serverReady.await() }
                advertisement.register("$name Sendspin", "_sendspin._tcp.", host.port,
                    mapOf("name" to "$name Sendspin", "path" to "/sendspin", "manufacturer" to "VACA_Vision", "model" to "Display and Dot"))
            } catch (_: Exception) { report(JSONObject().put("music_error", true)) }
        }
        scope.launch { client.controllerState.collect { value ->
            if (value != null) report(JSONObject().put("music_volume", value.volume).put("music_muted", value.muted))
        } }
        scope.launch { client.state.collect { report(JSONObject().put("sendspin_state", it.name.lowercase())) } }
        scope.launch { client.groupPlaybackState.collect {
            report(JSONObject().put("music_playing", it == GroupPlaybackState.PLAYING))
            if (it == GroupPlaybackState.PLAYING) prefs.edit().putString("last_server", client.serverId.value).apply()
        } }
        main.post(watchdog)
        report(JSONObject().put("reply_volume", replyVolume).put("sendspin_id", "vaca-vision-$uuid"))
    }
    fun command(action: String, data: JSONObject) { main.post {
        if (closed) return@post
        val id = data.optString("session")
        when (action) {
            "voice-begin" -> {
                if (id.isEmpty() || id.length > 80) return@post
                if (session != id) { finish("cancelled"); session = id; replyRequested = false }
                leaseUntil = SystemClock.elapsedRealtime() + 15000
                attenuation = data.optDouble("gain", 0.1).toFloat().coerceIn(0f, 1f)
                report(JSONObject().put("session", session).put("playback", "ready").put("music_duck_gain", attenuation))
            }
            "voice-keepalive" -> if (id == session) leaseUntil = SystemClock.elapsedRealtime() + 15000
            "voice-end" -> if (id == session) finish("cancelled")
            "voice-play" -> {
                if (id != session || id.isEmpty() || replyRequested) return@post
                val url = data.optString("url")
                val preannounce = data.optString("preannounce").takeIf { it.isNotEmpty() && it != "null" }
                val urls = listOfNotNull(preannounce, url)
                if (urls.any { value ->
                    val uri = android.net.Uri.parse(value)
                    uri.scheme !in listOf("http", "https") || uri.host.isNullOrEmpty() || uri.userInfo != null
                }) { finish("failed"); return@post }
                replyRequested = true
                try {
                    val mp = ExoPlayer.Builder(context).build()
                    player = mp
                    mp.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
                    mp.volume = replyVolume
                    mp.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (player !== mp || session != id) return
                            if (state == Player.STATE_READY && !replyStarted) {
                                replyStarted = true
                                report(JSONObject().put("session", id).put("playback", "playing"))
                            } else if (state == Player.STATE_ENDED) completeReply("finished")
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            if (player === mp && session == id) {
                                report(JSONObject().put("playback_error", error.errorCodeName))
                                completeReply("failed")
                            }
                        }
                    })
                    mp.setMediaItems(urls.map { MediaItem.fromUri(it) }); mp.prepare(); mp.play()
                } catch (_: Exception) { completeReply("failed") }
            }
            "reply-volume" -> {
                replyVolume = data.optDouble("volume", 0.7).toFloat().coerceIn(0f, 1f)
                prefs.edit().putFloat("reply_volume", replyVolume).apply()
                player?.volume = replyVolume
                report(JSONObject().put("reply_volume", replyVolume))
            }
            "music-command" -> client.sendControllerCommand(data.optString("command"), if (data.has("volume")) data.optInt("volume").coerceIn(0, 100) else null)
        }
    } }
    private fun completeReply(result: String) {
        player?.release(); player = null; replyStarted = false
        // Keep ducking until the session owner acknowledges completion (early TTS can precede pipeline end).
        report(JSONObject().put("session", session).put("playback", result))
    }
    private fun finish(result: String) {
        val old = session
        session = ""; attenuation = 1f; replyStarted = false; replyRequested = false
        player?.release(); player = null
        if (old.isNotEmpty()) report(JSONObject().put("session", old).put("playback", result).put("music_duck_gain", attenuation))
    }
    fun publishState() { main.post {
        if (!closed) report(JSONObject()
            .put("sendspin_id", "vaca-vision-$uuid")
            .put("sendspin_state", client.state.value.name.lowercase())
            .put("reply_volume", replyVolume)
            .put("music_duck_gain", attenuation)
            .put("music_playing", client.groupPlaybackState.value == GroupPlaybackState.PLAYING)
            .put("playback", if (replyStarted) "playing" else "idle")
            .put("music_volume", client.controllerState.value?.volume)
            .put("music_muted", client.controllerState.value?.muted))
    } }
    fun disconnected() { main.post { finish("disconnected") } }
    override fun close() {
        main.post { closed = true; main.removeCallbacks(watchdog); finish("closed") }
        client.disconnect(); host.stopServer(); advertisement.close(); scope.cancel()
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }
}
