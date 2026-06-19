/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SmokeRunResultTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `serialises to a single JSONL line and round-trips, nested stage breakdown included`() {
        val row =
            SmokeRunResult(
                ts = "2026-06-05T00:00:00Z",
                runNumber = "42",
                commit = "abc123",
                branch = "feat/x",
                provider = "deepseek",
                testClass = "ContractVocabularySmokeTest",
                testMethod = "parallel multi-instance activity",
                outcome = "fail",
                failureCategory = "classification",
                message = "Expected activity of type Service",
                failureSignature = "parallel multi-instance activity::Expected activity of type Service",
                failureHash = "deadbeef",
                servedModel = "deepseek-chat",
                costUsd = 0.0123,
                costKnown = "priced",
                promptTokens = 1500,
                completionTokens = 320,
                llmCallCount = 2,
                llmTimeMs = 8400,
                toolCallCount = 3,
                stageBreakdown =
                mapOf(
                    "readiness" to
                        StageStats(model = "haiku", promptTokens = 500, completionTokens = 80, llmCalls = 1),
                    "extraction" to
                        StageStats(model = "sonnet", promptTokens = 1000, completionTokens = 240, llmCalls = 1),
                ),
                diagnostics =
                listOf(
                    SmokeDiagnostic(
                        kind = "llm_parse_error",
                        exceptionClass = "KotlinInvalidNullException",
                        messageSignature = "missing SourceEvidence id",
                        messageHash = "beadfeed",
                        targetType = "dev.groknull.bpmner.readiness.SourceEvidence",
                        fieldPath = "id",
                        agentName = "readiness",
                        model = "deepseek-chat",
                        count = 2,
                        sample = "SourceEvidence id is missing",
                    ),
                ),
                diagnosticSummary = mapOf("llm_parse_error" to 2),
                testFingerprint = "aaaa",
                promptFingerprint = "bbbb",
                promptBaselineHash = "cccc",
                embabelVersion = "0.4.0",
                runComplete = true,
            )

        val json = mapper.writeValueAsString(row)

        assertFalse(json.contains("\n"), "JSONL rows must be single-line")
        assertEquals(row, mapper.readValue<SmokeRunResult>(json))
    }
}
