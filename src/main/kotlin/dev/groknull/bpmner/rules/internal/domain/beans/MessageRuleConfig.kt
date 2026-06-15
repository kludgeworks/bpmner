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
import dev.groknull.bpmner.rules.internal.domain.primitives.NlpPosTag
import dev.groknull.bpmner.rules.internal.domain.primitives.PartOfSpeechCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PartOfSpeechMode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(name = ["bpmner.rules.source"], havingValue = "kotlin")
@Suppress("MaxLineLength")
internal class MessageRuleConfig {
    @Bean
    fun msgMessageFlowAcrossPools(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Message Flow Across Pools",
        category = RuleCategory.Message,
        intent = "Ensure message flow models inter-participant communication.",
        forModellers = "Use message flows only between different pools or participants, not within a single pool.",
        forAI = "Detect message flows whose source and target resolve to the same pool or cannot be mapped to valid pools.",
        targetElements = listOf("bpmn:MessageFlow"),
        errorMessages = mapOf(
            "default" to "Message flow must connect elements in different pools",
        ),
        check = ConnectivityCheckConfig(mode = ConnectivityMode.ACROSS_POOLS),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
    )

    @Bean
    fun msgMessageFlowNamePattern(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Message Flow Name Pattern",
        category = RuleCategory.Message,
        intent = "Encourage noun-based message naming over action phrasing.",
        forModellers = "Label message flows with the message name, such as Approval confirmation, rather than an action such as Send approval.",
        forAI = "Detect message flow labels that start with a verb or auxiliary token.",
        targetElements = listOf("bpmn:MessageFlow"),
        errorMessages = mapOf(
            "default" to "Message flow name should describe the message, not an action",
        ),
        check = PartOfSpeechCheckConfig(
            property = "name",
            mode = PartOfSpeechMode.LEADING_MUST_NOT_BE,
            posClass = NlpPosTag.VERB,
        ),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
    )
}
