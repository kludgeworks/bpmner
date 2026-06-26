/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SmokeRunResultTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `serialises to a single JSONL line and round-trips, nested stage breakdown included`() {
        val row = sampleRow()

        val json = mapper.writeValueAsString(row)

        assertFalse(json.contains("\n"), "JSONL rows must be single-line")
        assertEquals(row, mapper.readValue<SmokeRunResult>(json))
    }

    private fun sampleRow(): SmokeRunResult = SmokeRunResult(
        ts = "2026-06-05T00:00:00Z",
        runNumber = "42",
        commit = "abc123",
        branch = "feat/x",
        provider = "deepseek",
        testClass = "ContractVocabularySmokeTest",
        testMethod = "parallel multi-instance activity",
        outcome = "fail",
        failureCategory = "classification",
        failureSignal = null,
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
        stageBreakdown = stageBreakdown(),
        roleBreakdown = roleBreakdown(),
        diagnostics = listOf(sampleDiagnostic()),
        diagnosticSummary = mapOf("llm_parse_error" to 2),
        testFingerprint = "aaaa",
        promptFingerprint = "bbbb",
        promptBaselineHash = "cccc",
        embabelVersion = "0.4.0",
        runComplete = true,
    )

    private fun stageBreakdown(): Map<String, StageStats> = mapOf(
        "readiness" to StageStats(model = "haiku", promptTokens = 500, completionTokens = 80, llmCalls = 1),
        "extraction" to StageStats(model = "sonnet", promptTokens = 1000, completionTokens = 240, llmCalls = 1),
    )

    private fun roleBreakdown(): Map<String, StageStats> = mapOf(
        "readiness-assessor" to StageStats(model = "haiku", promptTokens = 500, completionTokens = 80, llmCalls = 1),
        "contract-extractor" to StageStats(model = "sonnet", promptTokens = 1000, completionTokens = 240, llmCalls = 1),
    )

    private fun sampleDiagnostic(): SmokeDiagnostic = SmokeDiagnostic(
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
    )

    @Test
    fun `missing additive signal and role fields parse with safe defaults`() {
        val json =
            """
            {
              "ts":"2026-06-05T00:00:00Z",
              "runNumber":null,
              "commit":null,
              "branch":null,
              "provider":"deepseek",
              "testClass":"ContractVocabularySmokeTest",
              "testMethod":"parallel multi-instance activity",
              "outcome":"pass",
              "failureCategory":null,
              "message":null,
              "failureSignature":null,
              "failureHash":null,
              "servedModel":null,
              "costUsd":0.0,
              "costKnown":"unknown",
              "promptTokens":0,
              "completionTokens":0,
              "llmCallCount":0,
              "llmTimeMs":0,
              "toolCallCount":0,
              "stageBreakdown":{},
              "testFingerprint":"aaaa",
              "promptFingerprint":"bbbb",
              "promptBaselineHash":null,
              "embabelVersion":null,
              "runComplete":true
            }
            """.trimIndent()

        val row = mapper.readValue<SmokeRunResult>(json)

        assertNull(row.failureSignal)
        assertEquals(emptyMap<String, StageStats>(), row.roleBreakdown)
    }
}
