/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class StructuredOutputReliabilityTest {
    @Test
    fun `publishes INVALID_FORMAT and rethrows on InvalidLlmReturnFormatException`() {
        val eventPublisher = mock(ApplicationEventPublisher::class.java)
        val failure = InvalidLlmReturnFormatException(
            llmReturn = "not json",
            expectedType = String::class.java,
            cause = RuntimeException("malformed"),
        )

        val thrown = assertFailsWith<InvalidLlmReturnFormatException> {
            eventPublisher.publishOnInvalidLlmReturn("readiness") { throw failure }
        }

        assertSame(failure, thrown)
        val captor = org.mockito.ArgumentCaptor.forClass(StructuredOutputFailureEvent::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertEquals("readiness", captor.value.role)
        assertEquals(StructuredOutputFailureCategory.INVALID_FORMAT, captor.value.category)
    }

    @Test
    fun `publishes INVALID_TYPE and rethrows on InvalidLlmReturnTypeException`() {
        val eventPublisher = mock(ApplicationEventPublisher::class.java)
        val failure = InvalidLlmReturnTypeException(
            returnedObject = "bad",
            constraintViolations = emptySet(),
        )

        val thrown = assertFailsWith<InvalidLlmReturnTypeException> {
            eventPublisher.publishOnInvalidLlmReturn("alignment") { throw failure }
        }

        assertSame(failure, thrown)
        val captor = org.mockito.ArgumentCaptor.forClass(StructuredOutputFailureEvent::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertEquals("alignment", captor.value.role)
        assertEquals(StructuredOutputFailureCategory.INVALID_TYPE, captor.value.category)
    }

    @Test
    fun `does not intercept unrelated exceptions`() {
        val eventPublisher = mock(ApplicationEventPublisher::class.java)

        assertFailsWith<IllegalStateException> {
            eventPublisher.publishOnInvalidLlmReturn("readiness") { throw IllegalStateException("network blip") }
        }

        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `returns the block's result on success without publishing`() {
        val eventPublisher = mock(ApplicationEventPublisher::class.java)

        val result = eventPublisher.publishOnInvalidLlmReturn("readiness") { "ok" }

        assertEquals("ok", result)
        verifyNoInteractions(eventPublisher)
    }
}
