package com.soprso.dhurandharai.chat

import java.util.concurrent.atomic.AtomicBoolean

/** Prevents two rapid send actions from creating two messages. */
class SubmissionGuard {
    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }
}
