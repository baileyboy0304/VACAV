package com.mph1970.vacavision.audio

import org.json.JSONArray
import org.json.JSONObject

/** Bounded, numeric audio telemetry only: no audio, tokens, URLs or media titles. */
object AudioDiagnostics {
    private val samples = ArrayDeque<JSONObject>()
    private var state = "idle"
    private var focusChanges = 0
    private var lastFocus = 0
    @Synchronized fun state(value: String) { state = value }
    @Synchronized fun focus(change: Int) { focusChanges++; lastFocus = change }
    @Synchronized fun record(sample: JSONObject) {
        samples.addLast(JSONObject(sample.toString()).put("captured_at_ms", System.currentTimeMillis()))
        while (samples.size > 24) samples.removeFirst()
    }
    @Synchronized fun snapshot(): JSONObject = JSONObject()
        .put("schema", 1).put("app_version", "0.2.0")
        .put("state", state).put("focus_changes", focusChanges).put("last_focus", lastFocus)
        .put("samples", JSONArray(samples.map { JSONObject(it.toString()) }))

    @Synchronized fun summary(): String = buildString {
        append("Sendspin: $state\nAudio focus changes: $focusChanges\n\n")
        val latest = samples.lastOrNull()
        if (latest == null) append("Play audio to collect measurements.\n")
        else append(latest.toString(2))
        append("\n\nHome Assistant → Overlay integration → Download diagnostics saves the recent measurements. No ADB connection is needed.")
    }
}
