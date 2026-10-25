package com.msp1974.vacompanion.utils

/** Session-scoped ducking lease; stale commands cannot extend a later session. */
class VoiceLease {
    var session = ""
        private set
    private var deadline = 0L
    fun begin(token: String, now: Long) {
        require(token.isNotEmpty())
        session = token
        deadline = now + 12000
    }
    fun renew(token: String, now: Long) {
        if (token.isNotEmpty() && token == session) deadline = now + 12000
    }
    fun expired(now: Long) = session.isNotEmpty() && now >= deadline
    fun end(): String {
        val old = session
        session = ""
        deadline = 0
        return old
    }
}
