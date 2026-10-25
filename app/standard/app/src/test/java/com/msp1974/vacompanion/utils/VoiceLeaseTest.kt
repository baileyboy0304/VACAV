package com.msp1974.vacompanion.utils

import org.junit.Assert.*
import org.junit.Test

class VoiceLeaseTest {
    @Test fun expiresWithoutKeepalive() {
        val lease = VoiceLease(); lease.begin("a", 100)
        assertFalse(lease.expired(12099)); assertTrue(lease.expired(12100))
    }
    @Test fun staleKeepaliveCannotExtendNewSession() {
        val lease = VoiceLease(); lease.begin("a", 0); lease.begin("b", 100)
        lease.renew("a", 10000)
        assertTrue(lease.expired(12100))
    }
    @Test fun currentKeepaliveAndDisconnect() {
        val lease = VoiceLease(); lease.begin("a", 0); lease.renew("a", 5000)
        assertFalse(lease.expired(12000)); assertTrue(lease.expired(17000))
        assertEquals("a", lease.end()); assertFalse(lease.expired(18000))
    }
    @Test fun itvxOnlyMatchesExactFreshWarning() {
        val message = listOf<CharSequence>(ItvxWarningPolicy.MESSAGE)
        assertTrue(ItvxWarningPolicy.matches("air.ITVMobilePlayer", "android.app.Dialog", message, 10))
        assertFalse(ItvxWarningPolicy.matches("other.app", "android.app.Dialog", message, 10))
        assertFalse(ItvxWarningPolicy.matches("air.ITVMobilePlayer", "android.app.Dialog", message, 2000))
        assertFalse(ItvxWarningPolicy.matches("air.ITVMobilePlayer", "android.app.Dialog", listOf("Sign in failed"), 10))
    }
}
