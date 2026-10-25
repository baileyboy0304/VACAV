package com.mph1970.vacavision.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Last confirmed state of the paired Dot. Unknown/disconnected is not an unmute. */
object RemoteMicrophone {
    private val state = MutableStateFlow(false)
    val muted = state.asStateFlow()
    const val BAR_HEIGHT_DP = 4
    const val BAR_COLOR = 0xFFFF0000.toInt()
    fun update(value: JSONObject?) {
        (value?.opt("muted") as? Boolean)?.let { state.value = it }
    }
    fun reset() { state.value = false }
}
