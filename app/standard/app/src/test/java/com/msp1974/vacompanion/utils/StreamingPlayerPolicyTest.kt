package com.msp1974.vacompanion.utils

import org.junit.Assert.*
import org.junit.Test

class StreamingPlayerPolicyTest {
    private fun window(component: String, bounds: String = "[0,0][1280,800]", focus: Boolean = true) = """
    1: name='Window{abc u0 $component}', displayId=0, hasFocus=$focus, visible=true, flags=0x818101a0, frame=$bounds, ownerUid=10177
"""
    private fun session(pkg: String, active: Boolean = true, state: Int = 3, position: Int = 1000) = """
      package=$pkg
      active=$active
      state=PlaybackState {state=$state, position=$position, speed=1.0}
"""

    @Test fun onlyRecognizedFullscreenNativePlayers() {
        for (player in listOf(
            "bbc.iplayer.android/uk.co.bbc.iplayer.player.PlayerActivity",
            "air.ITVMobilePlayer/com.candyspace.itv.feature.player.PlayerActivity",
            "com.netflix.mediaclient.echo/com.netflix.mediaclient.ui.player.PlayerActivity")) {
            assertTrue(StreamingPlayerPolicy.eligible(window(player)))
            assertFalse(StreamingPlayerPolicy.eligible(window(player, "[500,300][1280,800]")))
            assertFalse(StreamingPlayerPolicy.eligible(window(player, focus = false)))
            assertFalse(StreamingPlayerPolicy.eligible(window(player).replace("visible=true", "visible=false")))
        }
        assertFalse(StreamingPlayerPolicy.eligible(window("com.mph1970.vacavision/com.msp1974.vacompanion.MainActivity")))
        assertFalse(StreamingPlayerPolicy.eligible(window("com.netflix.mediaclient.echo/com.netflix.mediaclient.ui.home.impl.HomeActivity")))
        assertFalse(StreamingPlayerPolicy.eligible("Permission Denial"))
    }

    @Test fun itvxLiveRequiresPlayerKeepAwakeFlag() {
        val live = window("air.ITVMobilePlayer/com.candyspace.itvplayer.ui.main.MainActivity")
        assertTrue(StreamingPlayerPolicy.eligible(live))
        assertFalse(StreamingPlayerPolicy.eligible(live.replace("0x818101a0", "0x81810120")))
        assertFalse(StreamingPlayerPolicy.eligible(live.replace("flags=0x818101a0,", "")))
        assertFalse(StreamingPlayerPolicy.eligible(live.replace("[0,0][1280,800]", "[500,300][1280,800]")))
    }

    @Test fun silkRequiresItsOwnActiveSessionIncludingPause() {
        val silk = window("com.amazon.cloud9/com.amazon.slate.echo.SlateEchoActivity")
        for (state in listOf(2, 3, 6)) assertTrue(StreamingPlayerPolicy.eligible(silk, session("com.amazon.cloud9", state = state)))
        assertFalse(StreamingPlayerPolicy.eligible(silk))
        assertFalse(StreamingPlayerPolicy.eligible(silk, session("com.netflix.mediaclient.echo")))
        assertFalse(StreamingPlayerPolicy.eligible(silk, session("com.amazon.cloud9", false) + session("com.netflix.mediaclient.echo")))
        assertFalse(StreamingPlayerPolicy.eligible(silk, session("com.amazon.cloud9", state = 1)))
        assertFalse(StreamingPlayerPolicy.eligible(silk, session("com.amazon.cloud9", state = 2, position = 0)))
        assertFalse(StreamingPlayerPolicy.eligible(silk.replace("[0,0]", "[0,133]"), session("com.amazon.cloud9")))
    }
}
