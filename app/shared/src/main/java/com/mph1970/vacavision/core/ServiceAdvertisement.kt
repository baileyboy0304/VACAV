package com.mph1970.vacavision.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

class ServiceAdvertisement(context: Context, private val status: (String) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()
    @Volatile private var closed = false
    @Synchronized fun register(name: String, type: String, port: Int, attributes: Map<String, String> = emptyMap()) {
        if (closed) return
        val info = NsdServiceInfo().apply {
            serviceName = name; serviceType = type; setPort(port)
            attributes.forEach { (key, value) -> setAttribute(key, value) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (closed) runCatching { manager.unregisterService(this) }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                synchronized(this@ServiceAdvertisement) { listeners.remove(this) }
                if (!closed) status("Discovery registration failed ($type, $code); use IP address")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        listeners.add(listener)
        try { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) { listeners.remove(listener); throw e }
    }
    @Synchronized override fun close() {
        closed = true
        listeners.forEach { runCatching { manager.unregisterService(it) } }
        listeners.clear()
    }
}
