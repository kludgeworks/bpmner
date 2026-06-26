/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal

import com.embabel.agent.api.common.Actor
import com.embabel.agent.config.models.anthropic.withAnthropicCaching
import com.embabel.agent.prompt.persona.Persona
import com.embabel.common.ai.model.LlmOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Ruleset-module actor configuration — the default linter persona.
 *
 * Bound at `bpmner` to preserve the existing `bpmner.linter` property key while placing
 * config ownership in the ruleset module (ADR-451 S4).
 */
@Validated
@ConfigurationProperties("bpmner")
internal data class BpmnRulesConfig(
    val linter: Actor<Persona> = DEFAULT_LINTER,
) {
    companion object {
        val DEFAULT_LINTER =
            Actor(
                persona =
                Persona(
                    name = "BPMN Linter",
                    persona =
                    "You are a meticulous BPMN 2.0 quality reviewer focused on labelling," +
                        " clarity, and modelling conventions",
                    objective =
                    "Evaluate LLM-judgement BPMN rules against a definition and report" +
                        " specific, element-anchored violations — never invent failures, never" +
                        " skip rules that apply",
                    voice = "specific and evidence-grounded",
                ),
                llm = cachingLlm("lint"),
            )

        private fun cachingLlm(role: String): LlmOptions = LlmOptions.withLlmForRole(role)
            .withAnthropicCaching(systemPrompt = true, tools = true)
    }
}

/**
 * Ruleset-module lint convention URI configuration.
 *
 * Bound at `bpmner.rules` to preserve the existing `bpmner.rules.config-uri` property key
 * while placing config ownership in the ruleset module (ADR-451 S4).
 */
@Validated
@ConfigurationProperties("bpmner.rules")
internal data class BpmnRulesUriConfig(
    // Modeller-owned lint convention source. Defaults to the packaged
    // `modulepath:/linter/pkl/bpmner.pkl`; set `bpmner.rules.config-uri` to a `file:` URI to load
    // team-specific word lists. Rule profile and per-rule severity overrides are read from
    // `bpmner.pkl` as part of [BpmnerLintConfig] (fields `profile` and `severityOverrides`).
    val configUri: String? = null,
)
