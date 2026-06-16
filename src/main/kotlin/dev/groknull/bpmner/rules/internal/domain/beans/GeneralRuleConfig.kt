/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleCategory
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.rules.BpmnerLintConfig
import dev.groknull.bpmner.rules.LlmRuleSpec
import dev.groknull.bpmner.rules.internal.domain.llmRule
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.rules.internal.domain.primitiveRule
import dev.groknull.bpmner.rules.internal.domain.primitives.CardinalityCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ElementConstraintCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ElementConstraintMode
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@Suppress("MaxLineLength")
internal class GeneralRuleConfig {
    // Deferred LLM metadata rules are added as LlmRuleSpec beans.
    // These are excluded from activeRules() but remain resolvable via ruleByIdOrAlias for markdown.
    @Bean
    fun genBusinessClarityOverTechnicalDetail(): LlmRuleSpec = llmRule(
        name = "Business Clarity Over Technical Detail",
        category = RuleCategory.General,
        intent = "Keep BPMN diagrams focused on business behavior rather than implementation mechanics.",
        forModellers = "Prefer clear business outcomes, responsibilities, and decisions over technical implementation details that obscure the process.",
        forAI = "Review labels and structure for business readability. Flag technical detail only when it dominates or obscures business intent.",
        targetElements = listOf("bpmn:Definitions", "bpmn:Process", "bpmn:FlowElement"),
        errorMessages = mapOf(
            "default" to "Business clarity over technical detail requires contextual review",
        ),
        severity = RuleSeverity.INFO,
        staticConfig = mapOf(
            "cookbookCode" to "GEN-02",
        ),
    )

    @Bean
    fun genBpmnSubset(nlp: BpmnNlp, lintConfig: BpmnerLintConfig): BpmnRule = primitiveRule(
        name = "BPMN Subset",
        category = RuleCategory.General,
        intent = "Keep models within the supported BPMN subset.",
        forModellers = "Use only the BPMN elements described in the supported BPMN subset and avoid unsupported exotic BPMN constructs.",
        forAI = "Detect discouraged BPMN types that are outside the supported subset and propose supported replacements.",
        targetElements = lintConfig.discouragedBpmnTypes,
        errorMessages = mapOf(
            "default" to "Element type is outside the supported BPMN subset",
        ),
        check = ElementConstraintCheckConfig(
            element = "bpmn:FlowElement",
            mode = ElementConstraintMode.ALLOWED_ELEMENT_SUBSET,
            constraints = mapOf("allowed" to ""),
        ),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
    )

    @Bean
    fun gen02NoDuplicateDiagrams(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "No Duplicate Diagrams",
        category = RuleCategory.General,
        intent = "Ensure BPMN documents contain a single diagram for downstream viewer compatibility.",
        forModellers = "Keep each BPMN document to one BPMN diagram entry so tools such as bpmn-js can load it reliably.",
        forAI = "Detect bpmn:Definitions roots containing more than one bpmndi:BPMNDiagram.",
        targetElements = listOf("bpmn:Definitions"),
        errorMessages = mapOf(
            "default" to "Multiple bpmndi:BPMNDiagram elements found. Only one diagram is allowed for compatibility with viewers like bpmn-js.",
        ),
        check = CardinalityCheckConfig(
            element = "bpmndi:BPMNDiagram",
            max = 1,
        ),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
        aliases = listOf("gen-02-no-duplicate-diagrams"),
    )
}
