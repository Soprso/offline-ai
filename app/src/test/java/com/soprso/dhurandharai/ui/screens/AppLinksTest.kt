package com.soprso.dhurandharai.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLinksTest {

    @Test
    fun privacyPolicyUsesTheExactPublishedUrl() {
        assertEquals(
            "https://shadow-studio-labs.netlify.app/privacy/offline-ai",
            AppLinks.PRIVACY_POLICY_URL,
        )
    }
}
