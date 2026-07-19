/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair

import com.embabel.agent.anthropic.withAnthropicCaching
import com.embabel.agent.api.common.Actor
import com.embabel.agent.prompt.persona.Persona
import com.embabel.common.ai.model.LlmOptions
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Repair-module configuration — repairer personas.
 *
 * Bound at `bpmner` to preserve existing operator-facing property keys while placing
 * config ownership in the repair module (ADR-009 S4).
 */
@Validated
@ConfigurationProperties("bpmner")
data class BpmnRepairConfig(
    val repairer: Actor<Persona> = DEFAULT_REPAIRER,
    val labelRepairer: Actor<Persona> = DEFAULT_LABEL_REPAIRER,
    val patchRepairer: Actor<Persona> = DEFAULT_PATCH_REPAIRER,
    val rewriteRepairer: Actor<Persona> = DEFAULT_REWRITE_REPAIRER,
) {
    companion object {
        private const val CONCISE_AND_EXACT = "concise and exact"

        val DEFAULT_LABEL_REPAIRER =
            Actor(
                persona =
                Persona(
                    name = "BPMN Label Copy Editor",
                    persona = "You are a fast, detail-oriented BPMN copy editor",
                    objective =
                    "Fix naming and label capitalization rules by providing targeted node and edge patches",
                    voice = CONCISE_AND_EXACT,
                ),
                llm = cachingLlm("repair-label"),
            )

        val DEFAULT_PATCH_REPAIRER =
            Actor(
                persona =
                Persona(
                    name = "BPMN Patch Repair Specialist",
                    persona = "You are a strict BPMN 2.0 graph topology validator and patch expert",
                    objective =
                    "Fix structural and routing validation errors by adding or removing" +
                        " specific elements without rewriting the whole definition",
                    voice = CONCISE_AND_EXACT,
                ),
                llm = cachingLlm("repair-patch"),
            )

        val DEFAULT_REWRITE_REPAIRER =
            Actor(
                persona =
                Persona(
                    name = "BPMN Full Rewrite Specialist",
                    persona = "You are an expert BPMN 2.0 validator who specializes in holistic process restructuring",
                    objective =
                    "Fix complex, cascading validation errors by rewriting the complete BPMN definition",
                    voice = CONCISE_AND_EXACT,
                ),
                llm = cachingLlm("repair-rewrite"),
            )

        val DEFAULT_REPAIRER =
            Actor(
                persona =
                Persona(
                    name = "BPMN Repair Specialist",
                    persona = "You are a strict BPMN 2.0 validator and repair expert",
                    objective =
                    "Fix every validation error in the BPMN definition" +
                        " and return the complete corrected object",
                    voice = CONCISE_AND_EXACT,
                ),
                llm = cachingLlm("repairer"),
            )

        private fun cachingLlm(role: String): LlmOptions = LlmOptions.withLlmForRole(role)
            .withAnthropicCaching(systemPrompt = true, tools = true)
    }
}

/**
 * Repair-module budget configuration.
 *
 * Bound at `bpmner.budget` to preserve the existing `bpmner.budget.maxRepairIterations`
 * property key while placing config ownership in the repair module (ADR-009 S4).
 *
 * [maxRepairIterations] bounds the [BpmnRepairLoop]: the loop exits as soon as there are no
 * blocking diagnostics or the iteration count reaches this ceiling, whichever comes first.
 * Keep this well below the generation budget so a stuck repair loop cannot exhaust the entire
 * GOAP budget.
 */
@Validated
@ConfigurationProperties("bpmner.budget")
data class BpmnRepairBudgetConfig(
    @field:Min(1)
    val maxRepairIterations: Int = DEFAULT_MAX_REPAIR_ITERATIONS,
) {
    companion object {
        const val DEFAULT_MAX_REPAIR_ITERATIONS = 5
    }
}
