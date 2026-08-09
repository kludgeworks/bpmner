/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.beans

import dev.groknull.bpmner.bpmn.BpmnRule
import dev.groknull.bpmner.bpmn.RuleCategory
import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.ruleset.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.ruleset.internal.domain.primitiveRule
import dev.groknull.bpmner.ruleset.internal.domain.primitives.ConnectivityCheckConfig
import dev.groknull.bpmner.ruleset.internal.domain.primitives.ConnectivityMode
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class FlowRuleConfig {
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
