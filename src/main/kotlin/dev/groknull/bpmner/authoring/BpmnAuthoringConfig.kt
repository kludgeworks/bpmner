/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import com.embabel.agent.api.common.Actor
import com.embabel.agent.config.models.anthropic.withAnthropicCaching
import com.embabel.agent.prompt.persona.Persona
import com.embabel.common.ai.model.LlmOptions
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Authoring-module configuration — the default generator persona.
 *
 * Bound at `bpmner` to preserve existing operator-facing property keys while placing
 * config ownership in the authoring module (ADR-451 S4).
 */
@Validated
@ConfigurationProperties("bpmner")
data class BpmnAuthoringConfig(
    val generator: Actor<Persona> = DEFAULT_GENERATOR,
) {
    companion object {
        val DEFAULT_GENERATOR =
            Actor(
                persona =
                Persona(
                    name = "BPMN Designer",
                    persona = "You are an expert BPMN 2.0 process modeller",
                    objective =
                    "Create a valid, well-structured BPMN process definition from a workflow description",
                    voice = "precise and thorough",
                ),
                llm = cachingLlm("generator"),
            )

        private fun cachingLlm(role: String): LlmOptions = LlmOptions.withLlmForRole(role)
            .withAnthropicCaching(systemPrompt = true, tools = true)
    }
}

/**
 * Authoring-module budget configuration.
 *
 * Bound at `bpmner.budget` to preserve the existing `bpmner.budget.generation` property key
 * while placing config ownership in the authoring module (ADR-451 S4).
 *
 * Generation and repair share a single budget because the repair loop chains into the
 * generation goal in one GOAP plan; lowering [generation] below today's ceiling risks budget
 * exhaustion on inputs that need substantial repair before reaching the `generateBpmn` goal.
 */
@Validated
@ConfigurationProperties("bpmner.budget")
data class BpmnAuthoringBudgetConfig(
    @field:Min(1)
    val generation: Int = 100,
)
