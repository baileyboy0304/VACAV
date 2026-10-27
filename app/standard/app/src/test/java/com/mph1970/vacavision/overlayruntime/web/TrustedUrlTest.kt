package com.mph1970.vacavision.overlayruntime.web
import org.junit.Assert.*
import org.junit.Test

class TrustedUrlTest {
    @Test fun originUsesSchemeHostAndEffectivePort() {
        assertTrue(TrustedUrl.sameOrigin("https://ha.local", "https://ha.local:443/view-assist/weather"))
        assertFalse(TrustedUrl.sameOrigin("https://ha.local", "http://ha.local"))
        assertFalse(TrustedUrl.sameOrigin("https://ha.local", "https://ha.local.evil.test"))
        assertFalse(TrustedUrl.sameOrigin("http://ha.local:8123", "http://ha.local:8080"))
    }
    @Test fun rejectsNavigationOutsideDashboardOrigin() {
        assertTrue(TrustedUrl.isDashboardPath("/view-assist/weather?unit=c"))
        for (path in listOf("//evil.test", "javascript:alert(1)", "https://evil.test", "/\\evil.test", "/a\nb")) assertFalse(path, TrustedUrl.isDashboardPath(path))
    }
    @Test fun validatesOriginBeforeCredentialsCanBeSent() {
        assertTrue(TrustedUrl.validOrigin("http://192.168.1.2:8123"))
        assertTrue(TrustedUrl.validOrigin("https://ha.local"))
        for (url in listOf("file:///tmp", "http://user:pass@ha", "http://ha/a", "http://ha?token=abc", "http://ha#x")) assertFalse(url, TrustedUrl.validOrigin(url))
    }
}
