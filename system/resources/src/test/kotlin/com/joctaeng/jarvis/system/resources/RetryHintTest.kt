package com.joctaeng.jarvis.system.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryHintTest {
    @Test
    fun readsTheFormsGoogleUses() {
        assertEquals(23_450L, RetryHint.millis("HTTP 429 Please retry in 23.45s."))
        assertEquals(23_000L, RetryHint.millis("{\"retryDelay\": \"23s\"}"))
        assertEquals((5 * 3600 + 48 * 60 + 11) * 1000L, RetryHint.millis("retry in 5h48m11s"))
        assertEquals(120_000L, RetryHint.millis("retry in 2m"))
        assertNull(RetryHint.millis("HTTP 429 RESOURCE_EXHAUSTED"))
    }

    @Test
    fun detectsDailyQuota() {
        assertTrue(RetryHint.isDaily("quotaId: GenerateRequestsPerDayPerProjectPerModel"))
    }

    @Test fun groqTryAgain() {
        kotlin.test.assertEquals(21_435L, RetryHint.millis("Rate limit reached ... Please try again in 21.435s. Need more tokens?"))
    }
}
