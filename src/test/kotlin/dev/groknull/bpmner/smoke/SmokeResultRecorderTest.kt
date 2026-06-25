/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException

class SmokeResultRecorderTest {

    @Test
    fun `zero-call quota and billing failures are no-signal infra`() {
        val failures = listOf(
            RuntimeException("HTTP 429 too many requests"),
            RuntimeException("Your credit balance is too low"),
            RuntimeException("credits depleted for this account"),
            RuntimeException("400 invalid_request_error: low credit balance"),
        )

        failures.forEach { failure ->
            assertEquals("infra", smokeCategoryFor(failure))
            assertEquals(SMOKE_FAILURE_SIGNAL_NO_SIGNAL, smokeFailureSignalFor("fail", failure, llmCallCount = 0))
        }
    }

    @Test
    fun `partial-call quota failures remain signal`() {
        val failure = RuntimeException("HTTP 429 too many requests")

        assertEquals("infra", smokeCategoryFor(failure))
        assertNull(smokeFailureSignalFor("fail", failure, llmCallCount = 1))
    }

    @Test
    fun `existing failure categories are preserved`() {
        assertEquals("classification", smokeCategoryFor(AssertionError("missing ServiceTask")))
        assertEquals("deterministic", smokeCategoryFor(IllegalStateException("bean missing")))
        assertEquals("infra", smokeCategoryFor(SocketTimeoutException("timed out")))
    }
}
