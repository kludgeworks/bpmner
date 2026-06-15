/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleCategory
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.rules.internal.domain.primitiveRule
import dev.groknull.bpmner.rules.internal.domain.primitives.ConnectivityCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ConnectivityMode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(name = ["bpmner.rules.source"], havingValue = "kotlin")
internal class FlowRuleConfig {
    @Bean
    fun flowDivergingFlowOutcomeLabel(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Diverging Flow Outcome Label",
        category = RuleCategory.Flow,
        intent = "Encourage explicit outcome labels on flows from diverging decision gateways.",
        forModellers = "Name flows leaving diverging exclusive, inclusive, or complex gateways " +
            "with outcome conditions such as Valid or Not eligible.",
        forAI = "Detect outgoing sequence flows from diverging exclusive, inclusive, or complex gateways " +
            "that have empty or missing labels.",
        targetElements = listOf("bpmn:ExclusiveGateway", "bpmn:InclusiveGateway", "bpmn:ComplexGateway"),
        errorMessages = mapOf(
            "default" to "Sequence flow from diverging gateway should use an outcome condition label",
        ),
        check = ConnectivityCheckConfig(mode = ConnectivityMode.OUTGOING_FLOWS_NAMED),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
    )

    @Bean
    fun flowSequenceFlowWithinPool(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Sequence Flow Within Pool",
        category = RuleCategory.Flow,
        intent = "Keep sequence flows within a single pool.",
        forModellers = "Use sequence flow only within the same pool; use message flow for communication between pools.",
        forAI = "Detect sequence flows whose source and target resolve to different pools.",
        targetElements = listOf("bpmn:SequenceFlow"),
        errorMessages = mapOf(
            "default" to "Sequence flow must not cross pool boundaries",
        ),
        check = ConnectivityCheckConfig(mode = ConnectivityMode.WITHIN_POOL),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
    )
}
