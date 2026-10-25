package com.msp1974.vacompanion.utils

import org.junit.Assert.*
import org.junit.Test

class OutsideDoubleTapTest {
    @Test fun doubleTapAndThirdTap() {
        val detector = OutsideDoubleTap()
        assertFalse(detector.tap(1000))
        assertTrue(detector.tap(1250))
        assertFalse(detector.tap(1300))
    }
    @Test fun slowAndDuplicateTouchesDoNotSummon() {
        val detector = OutsideDoubleTap()
        assertFalse(detector.tap(1000))
        assertFalse(detector.tap(1001))
        assertFalse(detector.tap(1500))
        detector.reset()
        assertFalse(detector.tap(1550))
    }
    @Test fun onlyOtherAppWindowsQualify() {
        fun window(component: String) = "  0: name='Window{abc u0 $component}', hasFocus=true, visible=true,"
        assertTrue(StreamingPlayerPolicy.outsideVision(window("bbc.iplayer.android/SignInActivity")))
        assertFalse(StreamingPlayerPolicy.outsideVision(window("com.mph1970.vacavision/MainActivity")))
        assertFalse(StreamingPlayerPolicy.outsideVision("unknown format"))
    }
}
