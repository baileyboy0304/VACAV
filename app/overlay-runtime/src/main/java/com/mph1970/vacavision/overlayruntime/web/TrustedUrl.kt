package com.mph1970.vacavision.overlayruntime.web

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import java.net.URI

object TrustedUrl {
    fun validOrigin(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme in listOf("http", "https") && !u.host.isNullOrBlank() && u.userInfo == null &&
            u.rawQuery == null && u.rawFragment == null && u.path in listOf("", "/")
    }.getOrDefault(false)
    fun sameOrigin(base: String, candidate: String): Boolean = runCatching {
        val a = URI(base); val b = URI(candidate)
        fun port(u: URI) = if (u.port >= 0) u.port else if (u.scheme == "https") 443 else 80
        a.scheme == b.scheme && a.host.equals(b.host, true) && port(a) == port(b) && b.userInfo == null
    }.getOrDefault(false)
    fun isDashboardPath(path: String): Boolean = runCatching {
        path.startsWith('/') && !path.startsWith("//") && !path.contains('\\') &&
            !path.any { it.code < 32 } && URI(path).host == null && URI(path).scheme == null
    }.getOrDefault(false)
}
