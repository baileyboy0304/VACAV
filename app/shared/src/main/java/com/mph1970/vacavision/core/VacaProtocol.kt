package com.mph1970.vacavision.core

import org.json.JSONArray
import org.json.JSONObject

/** Display protocol only; Crown's Wyoming and Sendspin services remain independent. */
object VacaProtocol {
    const val IDENTITY = "vaca-vision"
    const val PROTOCOL_VERSION = 1
    val PORT: Int get() = if (android.os.Build.DEVICE == "athena") 26900 else 10900
    const val SERVICE_TYPE = "_vaca-vision._tcp."
    const val CREDITS = "Based on VACA by MSP1974 and View Assist by Dinki. Independent variant."
    fun info(name: String, id: String = "test", version: String = "test"): JSONObject = JSONObject().apply {
        put("version", version)
        put("vision", JSONObject().apply {
            put("name", name); put("id", id); put("version", version)
            put("protocol", IDENTITY); put("protocol_version", PROTOCOL_VERSION)
            put("credits", CREDITS)
        })
    }
    fun capabilities(model: String, sdk: Int, release: String, overlayBuild: Boolean = true): JSONObject = JSONObject().apply {
        put("protocol", IDENTITY); put("protocol_version", PROTOCOL_VERSION)
        put("device_signature", model); put("app_version", release)
        put("sdk_version", sdk); put("release", release); put("webview_version", "system")
        put("sensors", JSONArray().put("current_path"))
        put("overlay", overlayBuild); put("display_only", true)
        put("sendspin", false); put("dot_playback", true); put("native_audio", false); put("crown_status", !overlayBuild)
        put("alarm_popup", overlayBuild); put("picker_profiles", if (overlayBuild) 1 else 0)
        put("roles", JSONArray(VisionRole.supported(overlayBuild).map { it.id }))
    }
    fun custom(type: String, data: JSONObject) = WireEvent("custom-event", JSONObject().put("event_type", type).put("data", data))
    fun body(data: JSONObject): JSONObject = data.optJSONObject("data") ?: data
}
