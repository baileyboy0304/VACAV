package com.mph1970.vacavision.core

import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

data class WireEvent(val type: String, val data: JSONObject = JSONObject(), val payload: ByteArray = byteArrayOf())

/** Wyoming is byte framed, not a stream of JSON lines: data/payload have byte lengths. */
object WyomingWire {
    private const val MAX_HEADER = 65536
    private const val MAX_DATA = 1048576
    private const val MAX_PAYLOAD = 4194304
    fun read(input: InputStream): WireEvent? {
        val header = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) { if (header.size() == 0) return null else throw EOFException("Incomplete Wyoming header") }
            if (b == 10) break
            require(header.size() < MAX_HEADER) { "Wyoming header too large" }
            header.write(b)
        }
        val json = JSONObject(header.toString("UTF-8"))
        val dataLength = json.optInt("data_length", 0)
        val payloadLength = json.optInt("payload_length", 0)
        require(dataLength in 0..MAX_DATA && payloadLength in 0..MAX_PAYLOAD) { "Invalid Wyoming frame length" }
        val data = json.optJSONObject("data") ?: JSONObject()
        if (dataLength > 0) {
            val extra = JSONObject(String(readExactly(input, dataLength), Charsets.UTF_8))
            extra.keys().forEach { data.put(it, extra.get(it)) }
        }
        return WireEvent(json.getString("type"), data, readExactly(input, payloadLength))
    }
    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(result, offset, count - offset)
            if (n < 0) throw EOFException("Incomplete Wyoming body")
            if (n > 0) offset += n
        }
        return result
    }
    fun write(output: OutputStream, event: WireEvent) {
        val data = event.data.toString().toByteArray(Charsets.UTF_8)
        val header = JSONObject().put("type", event.type).put("version", "1.0.0").put("data_length", data.size)
        if (event.payload.isNotEmpty()) header.put("payload_length", event.payload.size)
        output.write((header.toString() + "\n").toByteArray(Charsets.UTF_8))
        output.write(data)
        output.write(event.payload)
        output.flush()
    }
}
