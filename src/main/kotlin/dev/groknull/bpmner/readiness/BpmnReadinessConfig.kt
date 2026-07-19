/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

import com.embabel.agent.anthropic.withAnthropicCaching
import com.embabel.agent.api.common.Actor
import com.embabel.agent.prompt.persona.Persona
import com.embabel.common.ai.model.LlmOptions
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Readiness-module actor configuration — the default readiness-assessor persona.
 *
 * Bound at `bpmner` to preserve the existing `bpmner.readinessAssessor` property key while
 * placing config ownership in the readiness module (ADR-009 S4).
 */
@Validated
@ConfigurationProperties("bpmner")
data class BpmnReadinessConfig(
    val readinessAssessor: Actor<Persona> = DEFAULT_READINESS_ASSESSOR,
) {
    companion object {
        val DEFAULT_READINESS_ASSESSOR =
            Actor(
                persona =
                Persona(
                    name = "BPMN Readiness Assessor",
                    persona =
                    "You are a conservative workflow readiness reviewer. You accept any" +
                        " repeatable sequenced workflow — business, automated, technical, scientific," +
                        " or personal — and you only block inputs that genuinely lack workflow structure",
                    objective =
                    "Assess whether source text contains enough grounded process detail" +
                        " for BPMN generation without inventing missing facts",
                    voice = "specific and evidence-grounded",
                ),
                llm = cachingLlm("readiness-assessor"),
            )

        private fun cachingLlm(role: String): LlmOptions = LlmOptions.withLlmForRole(role)
            .withAnthropicCaching(systemPrompt = true, tools = true)
    }
}

/**
 * Readiness-module budget configuration.
 *
 * Bound at `bpmner.budget` to preserve the existing `bpmner.budget.readiness` property key
 * while placing config ownership in the readiness module (ADR-009 S4).
 */
@Validated
@ConfigurationProperties("bpmner.budget")
data class BpmnReadinessBudgetConfig(
    @field:Min(1)
    val readiness: Int = 20,
)

/**
 * Readiness-module threshold configuration — scoring thresholds and clarification limits.
 *
 * Bound at `bpmner.readiness` to preserve existing operator-facing property keys while
 * placing config ownership in the readiness module (ADR-009 S4).
 */
@Validated
@ConfigurationProperties("bpmner.readiness")
data class BpmnReadinessThresholdsConfig(
    @field:Min(0)
    @field:Max(MAX_PERCENT_SCORE)
    val readyThreshold: Int = 75,
    @field:Min(1)
    val maxClarificationQuestions: Int = 5,
) {
    companion object {
        const val MAX_PERCENT_SCORE = 100L
    }
}
