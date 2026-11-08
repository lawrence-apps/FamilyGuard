package com.familyguard.parent.data

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Global session-state signal.
 *
 * Emitted whenever the backend rejects our token (HTTP 401 or WebSocket close 4001),
 * e.g. after the server was redeployed with a fresh database. The UI collects this
 * and flips back to the login screen once, so the user never stares at a silently
 * broken dashboard — and the saved token is cleared so the next launch is clean.
 */
object Session {

    val expired = MutableSharedFlow<String>(extraBufferCapacity = 1)

    @Volatile
    private var kicked = false

    fun kick(reason: String) {
        if (kicked) return
        kicked = true
        Store.token = ""
        expired.tryEmit(reason)
    }

    /** Reset after a successful login so future 401s kick again. */
    fun armed() {
        kicked = false
    }
}
