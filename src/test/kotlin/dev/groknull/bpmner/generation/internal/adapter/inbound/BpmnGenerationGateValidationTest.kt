/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentScope
import com.embabel.agent.spi.validation.GoapPathToCompletionValidator
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnRequestResolver
import dev.groknull.bpmner.core.InputPathResolver
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessReportWriter
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Static-validation regression guard for [BpmnGenerationGateAgent].
 *
 * Reproduces the per-agent GOAP path-to-completion validation the platform runs at startup
 * (`GoapPathToCompletionValidator`, also wrapped by `AgentMetadataReader`). The validator plans over this
 * agent's actions in isolation and ignores `startingInputTypes`, so the agent must contain an in-scope,
 * non-cyclic producer of [ProcessInputAssessment] ([BpmnGenerationGateAgent.assessRequestReadiness]) for
 * the readiness goal to be reachable. Without it the validator reports `NO_PATH_TO_GOAL`.
 */
class BpmnGenerationGateValidationTest {
    @Test
    fun `gate agent passes static GOAP path-to-completion validation`(
        @TempDir tempDir: Path,
    ) {
        val agent =
            BpmnGenerationGateAgent(
                config = BpmnConfig(),
                requestResolver = BpmnRequestResolver(InputPathResolver(cwd = tempDir)),
                readinessInvoker = StubInvoker,
                readinessReportWriter = ReadinessReportWriter { _, _, _ -> "readiness.md" },
            )

        val scope: AgentScope = AgentMetadataReader().createAgentMetadata(agent)
            ?: error("createAgentMetadata returned null")

        val result = GoapPathToCompletionValidator().validate(scope)

        assertTrue(
            result.isValid,
            "Gate agent failed static validation: ${result.errors.joinToString { "${it.code}: ${it.message}" }}",
        )
    }

    private object StubInvoker : BpmnReadinessInvoker {
        override fun assess(request: BpmnRequest) = ProcessInputAssessment(
            verdict = ReadinessVerdict.READY,
            overallScore = 100,
            dimensions = emptyList(),
            clarificationQuestions = emptyList(),
            rationale = "stub",
        )
    }
}
