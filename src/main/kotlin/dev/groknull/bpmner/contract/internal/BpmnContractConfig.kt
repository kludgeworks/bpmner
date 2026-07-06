/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal

import com.embabel.agent.api.common.Actor
import com.embabel.agent.config.models.anthropic.withAnthropicCaching
import com.embabel.agent.prompt.persona.Persona
import com.embabel.common.ai.model.LlmOptions
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Contract-module actor configuration — the default contract-extractor persona.
 *
 * Bound at `bpmner` to preserve the existing `bpmner.contractExtractor` property key while
 * placing config ownership in the contract module (ADR-009 S4).
 */
@Validated
@ConfigurationProperties("bpmner")
internal data class BpmnContractConfig(
    val contractExtractor: Actor<Persona> = DEFAULT_CONTRACT_EXTRACTOR,
) {
    companion object {
        val DEFAULT_CONTRACT_EXTRACTOR =
            Actor(
                persona =
                Persona(
                    name = "Process Contract Extractor",
                    persona =
                    "You are a conservative workflow analyst who extracts source-grounded process" +
                        " contracts from any kind of sequenced workflow (business, automated, technical," +
                        " scientific, or personal)",
                    objective =
                    "Produce a typed ProcessContract whose every element is traceable to the source" +
                        " input, an assessment evidence id, a clarification answer, or an explicit" +
                        " assumption; never invent facts that are not grounded",
                    voice = "specific and evidence-grounded",
                ),
                llm = cachingLlm("contract-extractor"),
            )

        private fun cachingLlm(role: String): LlmOptions = LlmOptions.withLlmForRole(role)
            .withAnthropicCaching(systemPrompt = true, tools = true)
    }
}

/**
 * Contract-module threshold configuration — extraction limits.
 *
 * Bound at `bpmner.contract` to preserve the existing `bpmner.contract.maxAssumptions`
 * property key while placing config ownership in the contract module (ADR-009 S4).
 */
@Validated
@ConfigurationProperties("bpmner.contract")
internal data class BpmnContractThresholdsConfig(
    @field:Min(0)
    val maxAssumptions: Int = 10,
)
