package com.soprso.dhurandharai.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionGuardTest {
    @Test
    fun twoRapidAttemptsAcceptOnlyOneSubmission() {
        val guard = SubmissionGuard()

        assertTrue(guard.tryAcquire())
        assertFalse(guard.tryAcquire())
        assertFalse(guard.tryAcquire())
    }

    @Test
    fun releaseAllowsAnIntentionalRepeat() {
        val guard = SubmissionGuard()

        assertTrue(guard.tryAcquire())
        guard.release()
        assertTrue(guard.tryAcquire())
    }
}
