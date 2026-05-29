/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.investigation

import com.embabel.common.ai.converters.JacksonOutputConverter
import com.fasterxml.jackson.databind.ObjectMapper
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.internal.adapter.inbound.BpmnContractPromptFactory
import dev.groknull.bpmner.core.BpmnContractConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.io.path.readText

/**
 * Offline probe (no LLM call): measures the contract-extraction request payload size so we can
 * judge whether trimming fits under GitHub Models' 8K input-token cap.
 *
 * Spike-only investigation aid for #293. Safe to delete once we have a path forward.
 */
class ContractPromptSizeProbeTest {
    @Test
    fun `print contract extraction prompt and schema sizes`() {
        val prose = loadSample("employee-onboarding.prose.md")
        val factory = BpmnContractPromptFactory(BpmnContractConfig())
        val request = BpmnRequest(
            processDescription = prose,
            outputFile = null,
        )
        val assessment = ProcessInputAssessment(
            verdict = ReadinessVerdict.READY,
            overallScore = 90,
            dimensions = emptyList(),
            evidence = emptyList(),
            rationale = "Source is ready for extraction",
        )

        val prompt = factory.prompt(request, assessment, emptyList())
        val schema = JacksonOutputConverter(ProcessContract::class.java, ObjectMapper()).jsonSchema

        val promptChars = prompt.length
        val schemaChars = schema.length
        val totalChars = promptChars + schemaChars
        // Rough heuristic: English prose ~4 chars/token; JSON schema text ~3 chars/token (denser).
        val promptTokensApprox = promptChars / 4
        val schemaTokensApprox = schemaChars / 3
        val totalTokensApprox = promptTokensApprox + schemaTokensApprox

        println()
        println("=== ContractPromptSizeProbe ===")
        println("Prompt:        $promptChars chars  (~$promptTokensApprox tokens @ 4 c/t)")
        println("Schema:        $schemaChars chars  (~$schemaTokensApprox tokens @ 3 c/t)")
        println("Total:         $totalChars chars  (~$totalTokensApprox tokens)")
        println("GH Models cap: 8000 tokens (free tier per-request input)")
        println("Headroom:      ${8000 - totalTokensApprox} tokens")
        println()
        println("--- schema first 600 chars ---")
        println(schema.take(600))
        println("--- schema last 400 chars ---")
        println(schema.takeLast(400))
        println("--- end probe ---")
        println()
    }

    private fun loadSample(name: String): String {
        val testSrcDir = System.getenv("TEST_SRCDIR") ?: error("TEST_SRCDIR not set (run via Bazel)")
        val testWorkspace = System.getenv("TEST_WORKSPACE") ?: error("TEST_WORKSPACE not set (run via Bazel)")
        return Paths.get(testSrcDir, testWorkspace, "samples", name).readText()
    }
}
