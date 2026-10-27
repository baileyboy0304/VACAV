package com.mph1970.vacavision.overlayruntime.picker

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Requests travel only on the active Overlay integration connection. Never replay on reconnect. */
object PickerProfileClient {
    @Volatile var send: ((JSONObject) -> Boolean)? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    suspend fun request(operation: String, payload: JSONObject = JSONObject()): JSONObject {
        val id = UUID.randomUUID().toString(); val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        try {
            check(send?.invoke(JSONObject().put("request_id", id).put("operation", operation).put("payload", payload)) == true) {
                "Connect to Overlay integration 1.1.0 or later."
            }
            val result = withTimeout(15000) { reply.await() }
            check(result.optBoolean("ok")) { result.optString("error", "Profile request failed.") }
            return result.getJSONObject("result")
        } finally { pending.remove(id) }
    }
    fun receive(data: JSONObject) { pending.remove(data.optString("request_id"))?.complete(data) }
    fun disconnect() {
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Home Assistant disconnected. Check whether the operation completed before retrying.")) }
        pending.clear()
    }
}
