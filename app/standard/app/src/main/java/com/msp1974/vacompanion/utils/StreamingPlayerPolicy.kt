package com.msp1974.vacompanion.utils

/** Parse status only. Unknown firmware output fails closed; never inspect page/UI contents. */
internal object StreamingPlayerPolicy {
    private val windows = Regex("(?m)^[ \\t]+\\d+: name='Window\\{\\S+ u\\d+ ([^}]+)\\}', (.+)$")
    private val frame = Regex("frame=\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
    private val players = setOf(
        "bbc.iplayer.android/uk.co.bbc.iplayer.player.PlayerActivity",
        "air.ITVMobilePlayer/com.candyspace.itv.feature.player.PlayerActivity",
        "com.netflix.mediaclient.echo/com.netflix.mediaclient.ui.player.PlayerActivity",
    )
    private const val silk = "com.amazon.cloud9/com.amazon.slate.echo.SlateEchoActivity"
    private const val itvxLive = "air.ITVMobilePlayer/com.candyspace.itvplayer.ui.main.MainActivity"

    fun focusedPlayer(windowStatus: String, width: Int = 1280, height: Int = 800): String? {
        val match = windows.findAll(windowStatus).firstOrNull {
            it.groupValues[2].contains("hasFocus=true,") && it.groupValues[2].contains("visible=true,")
        } ?: return null
        val (component, attributes) = match.destructured
        if (component !in players && component != silk && component != itvxLive) return null
        // ITVX live playback shares its browsing activity. The player sets
        // KEEP_SCREEN_ON and clears it on exit; activity name alone is unsafe.
        if (component == itvxLive) {
            val flags = Regex("flags=0x([0-9a-fA-F]+),").find(attributes)
                ?.groupValues?.get(1)?.toLongOrNull(16) ?: return null
            if (flags and 0x80L == 0L) return null
        }
        val bounds = frame.find(attributes)?.groupValues?.drop(1)?.map { it.toInt() } ?: return null
        if (bounds[0] != 0 || bounds[1] != 0 || bounds[2] != width || bounds[3] < height - 16) return null
        return component.substringBefore('/')
    }

    fun eligible(windowStatus: String, mediaStatus: String = "", width: Int = 1280, height: Int = 800): Boolean {
        val player = focusedPlayer(windowStatus, width, height) ?: return false
        if (player != "com.amazon.cloud9") return true
        // Apple TV/Silk leaves an active paused session at position zero on exit.
        // A paused session with a real position still belongs to the player. Fail
        // closed for an ambiguous pause at the very start. Other apps do not count.
        val sessions = Regex("(?m)(?=^[ \\t]+package=)").split(mediaStatus)
        return sessions.any { session ->
            val playback = Regex("state=PlaybackState \\{state=(\\d+), position=(-?\\d+),").find(session)
            val state = playback?.groupValues?.get(1)?.toIntOrNull()
            val position = playback?.groupValues?.get(2)?.toLongOrNull() ?: -1
            session.lineSequence().first().trim() == "package=com.amazon.cloud9" &&
                Regex("(?m)^\\s+active=true\\s*$").containsMatchIn(session) &&
                (state == 3 || state == 6 || (state == 2 && position > 0))
        }
    }
}
