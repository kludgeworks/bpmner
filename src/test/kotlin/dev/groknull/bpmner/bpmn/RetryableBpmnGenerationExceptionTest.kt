/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

import com.embabel.agent.core.Retryable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryableBpmnGenerationExceptionTest {
    @Test
    fun `exception implements Retryable marker`() {
        val exception = RetryableBpmnGenerationException("test message")

        assertTrue(exception is Retryable, "exception should implement Retryable marker")
    }

    @Test
    fun `exception with cause preserves cause`() {
        val cause = RuntimeException("original cause")
        val exception = RetryableBpmnGenerationException("test message", cause)

        assertEquals(cause, exception.cause)
    }

    @Test
    fun `exception message is preserved`() {
        val message = "test error message"
        val exception = RetryableBpmnGenerationException(message)

        assertEquals(message, exception.message)
    }

    @Test
    fun `exception with cause preserves both message and cause`() {
        val message = "test error message"
        val cause = RuntimeException("original cause")
        val exception = RetryableBpmnGenerationException(message, cause)

        assertEquals(message, exception.message)
        assertEquals(cause, exception.cause)
        assertTrue(exception is Retryable)
    }

    @Test
    fun `null cause is acceptable`() {
        val exception = RetryableBpmnGenerationException("test message", null)

        assertEquals("test message", exception.message)
        assertNull(exception.cause)
        assertTrue(exception is Retryable)
    }

    @Test
    fun `empty message is acceptable`() {
        val exception = RetryableBpmnGenerationException("")

        assertEquals("", exception.message)
        assertTrue(exception is Retryable)
    }
}
