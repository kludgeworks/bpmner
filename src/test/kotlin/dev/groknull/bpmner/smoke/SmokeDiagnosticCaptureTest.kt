/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import ch.qos.logback.classic.Level
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SmokeDiagnosticCaptureTest {
    @Test
    fun `captures converter nullability failure with target type and field`() {
        val diagnostic =
            SmokeDiagnosticCapture.diagnosticFor(
                loggerName = "com.embabel.agent.spi.support.springai.ExceptionWrappingConverter",
                message =
                "Error com.fasterxml.jackson.module.kotlin.KotlinInvalidNullException: " +
                    "Instantiation of [simple type, class dev.groknull.bpmner.readiness.SourceEvidence] " +
                    "value failed for JSON property id due to missing value for creator parameter id",
            )

        assertNotNull(diagnostic)
        requireNotNull(diagnostic)
        assertEquals("llm_parse_error", diagnostic.kind)
        assertEquals("com.fasterxml.jackson.module.kotlin.KotlinInvalidNullException", diagnostic.exceptionClass)
        assertEquals("dev.groknull.bpmner.readiness.SourceEvidence", diagnostic.targetType)
        assertEquals("id", diagnostic.fieldPath)
        assertEquals(1, diagnostic.count)
    }

    @Test
    fun `captures invalid llm return retry with agent name`() {
        val diagnostic =
            SmokeDiagnosticCapture.diagnosticFor(
                loggerName = "com.embabel.agent.spi.support.springai.LlmDataBindingProperties",
                message =
                "LLM invocation dev.groknull.bpmner.readiness.internal.adapter.inbound.BpmnReadinessAgent" +
                    ".assessReadiness-dev.groknull.bpmner.readiness.ProcessInputAssessment-3: " +
                    "Retry attempt 1 of 10 due to: Invalid LLM return when expecting " +
                    "dev.groknull.bpmner.readiness.ProcessInputAssessment: " +
                    "Root cause=com.fasterxml.jackson.module.kotlin.KotlinInvalidNullException: missing id",
            )

        assertNotNull(diagnostic)
        requireNotNull(diagnostic)
        assertEquals("invalid_llm_return", diagnostic.kind)
        assertEquals("com.fasterxml.jackson.module.kotlin.KotlinInvalidNullException", diagnostic.exceptionClass)
        assertEquals("dev.groknull.bpmner.readiness.ProcessInputAssessment", diagnostic.targetType)
        assertEquals(
            "dev.groknull.bpmner.readiness.internal.adapter.inbound.BpmnReadinessAgent" +
                ".assessReadiness-dev.groknull.bpmner.readiness.ProcessInputAssessment-3",
            diagnostic.agentName,
        )
    }

    @Test
    fun `ignores ordinary unrelated logs`() {
        assertNull(
            SmokeDiagnosticCapture.diagnosticFor(
                loggerName = "dev.groknull.bpmner.SomeClass",
                message = "Application started",
            ),
        )
    }

    @Test
    fun `ignores info level operational action logs`() {
        assertNull(
            SmokeDiagnosticCapture.diagnosticFor(
                loggerName = "com.embabel.agent.core.ActionRunner",
                message = "Executing action assessReadiness",
                level = Level.INFO,
            ),
        )
    }
}
