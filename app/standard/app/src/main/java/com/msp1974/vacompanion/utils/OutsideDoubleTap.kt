package com.msp1974.vacompanion.utils

/** ACTION_OUTSIDE coordinates are hidden by Android; recognise timing only. */
internal class OutsideDoubleTap {
    private var first: Long? = null
    fun tap(now: Long): Boolean {
        val previous = first
        if (previous != null && now - previous in 40..350) {
            first = null // A third tap is not another double tap.
            return true
        }
        first = now
        return false
    }
    fun reset() { first = null }
}
