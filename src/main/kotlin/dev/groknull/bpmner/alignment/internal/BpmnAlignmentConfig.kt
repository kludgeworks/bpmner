/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment.internal

import com.embabel.agent.api.common.Actor
import com.embabel.agent.prompt.persona.Persona
import dev.groknull.bpmner.llm.defaultRoleLlmOptions
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Alignment-module actor configuration — the default alignment-validator persona.
 *
 * Bound at `bpmner` to preserve the existing `bpmner.alignmentValidator` property key while
 * placing config ownership in the alignment module (ADR-009 S4).
 */
@Validated
@ConfigurationProperties("bpmner")
internal data class BpmnAlignmentConfig(
    val alignmentValidator: Actor<Persona> = DEFAULT_ALIGNMENT_VALIDATOR,
) {
    companion object {
        val DEFAULT_ALIGNMENT_VALIDATOR =
            Actor(
                persona =
                Persona(
                    name = "BPMN Alignment Guard",
                    persona = "You are a strict BPMN semantic validator",
                    objective =
                    "Verify that generated BPMN process matches the process contract exactly;" +
                        " flag any invented tasks, missing branches, or unsupported end states",
                    voice = "critical and precise",
                ),
                llm = defaultRoleLlmOptions("alignment-validator"),
            )
    }
}

/**
 * Alignment-module threshold configuration — alignment strictness settings.
 *
 * Bound at `bpmner.alignment` to preserve existing operator-facing property keys while
 * placing config ownership in the alignment module (ADR-009 S4).
 */
@Validated
@ConfigurationProperties("bpmner.alignment")
internal data class BpmnAlignmentThresholdsConfig(
    @field:Min(0)
    val maxAssumptions: Int = 3,
    val blockOnUnsupportedElements: Boolean = true,
    val blockOnMissingContractItems: Boolean = true,
)
