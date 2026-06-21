/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.bpmn.BpmnRule
import dev.groknull.bpmner.bpmn.RuleCategory
import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.rules.internal.domain.primitiveRule
import dev.groknull.bpmner.rules.internal.domain.primitives.PoolLabelCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PoolLabelMode
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@Suppress("MaxLineLength")
internal class PoolRuleConfig {
    companion object {
        // DSL string literal shared across all three @Bean methods in this class.
        private val PARTICIPANT = listOf("bpmn:Participant")
    }

    @Bean
    fun poolBlackBoxPoolNamedByExternalEntityOrProcess(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Black Box Pool Named By External Entity Or Process",
        category = RuleCategory.Pool,
        intent = "Ensure black-box pools are identifiable external participants.",
        forModellers = "Name black-box pools using the external entity, organization, department, system, or external process they represent.",
        forAI = "For pools without a process reference, deterministically require a non-empty label; semantic entity checks require modelling context.",
        targetElements = PARTICIPANT,
        errorMessages = mapOf(
            "default" to "Black-box pool must have a name",
        ),
        check = PoolLabelCheckConfig(mode = PoolLabelMode.BLACK_BOX_NAMED_BY_EXTERNAL_ENTITY_OR_PROCESS),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
    )

    @Bean
    fun poolChildDiagramsKeepPoolProcessName(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Child Diagrams Keep Pool Process Name",
        category = RuleCategory.Pool,
        intent = "Keep child-level pool labels aligned with the upper-level process name.",
        forModellers = "When a child diagram elaborates a subprocess, keep the pool label as the upper-level process name rather than renaming it to the subprocess.",
        forAI = "Compare parent and child diagram context when available; a single BPMN XML document does not reliably prove cross-level naming intent.",
        targetElements = PARTICIPANT,
        errorMessages = mapOf(
            "default" to "Child diagram pool should keep the parent process name",
        ),
        check = PoolLabelCheckConfig(mode = PoolLabelMode.CHILD_DIAGRAMS_KEEP_POOL_PROCESS_NAME),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
    )

    @Bean
    fun poolWhiteBoxPoolNamedByProcess(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "White Box Pool Named By Process",
        category = RuleCategory.Pool,
        intent = "Name white-box pools after the process they expose.",
        forModellers = "Use the process name as the label of a white-box pool, not an organization, department, or role.",
        forAI = "For pools with a process reference, compare the participant label with the referenced process label when both are present.",
        targetElements = PARTICIPANT,
        errorMessages = mapOf(
            "default" to "White-box pool name should match the referenced process name",
        ),
        check = PoolLabelCheckConfig(mode = PoolLabelMode.WHITE_BOX_NAMED_BY_PROCESS),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
    )
}
