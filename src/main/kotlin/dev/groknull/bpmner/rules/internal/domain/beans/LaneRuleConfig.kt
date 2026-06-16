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
import dev.groknull.bpmner.rules.internal.domain.primitives.PoolLabelCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PoolLabelMode
import dev.groknull.bpmner.rules.internal.domain.primitives.PresenceCheckConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@Suppress("MaxLineLength")
internal class LaneRuleConfig {
    @Bean
    fun laneActorArtifactUsage(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Actor Artifact Usage",
        category = RuleCategory.Lane,
        intent = "Treat Actor custom artifacts as lane clarification only.",
        forModellers = "Use an Actor custom artifact inside a lane only to clarify who performs the lane activities; it does not replace the lane.",
        forAI = "Do not infer additional participants, lanes, control flow, or responsibility semantics from Actor artifacts.",
        targetElements = listOf("bpmn:Artifact"),
        errorMessages = mapOf(
            "default" to "Actor artifacts are documentation only and require modelling context",
        ),
        check = PresenceCheckConfig,
        nlp = nlp,
        severity = RuleSeverity.INFO,
    )

    @Bean
    fun laneLaneLabelsBusinessRolesPerformers(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Lane Labels Business Roles Performers",
        category = RuleCategory.Lane,
        intent = "Require lane labels that identify the responsible business role or performer.",
        forModellers = "Name each lane by the business role or performer responsible for the activities in that lane.",
        forAI = "Deterministically require lane labels to be present; judging whether the label is the correct role requires business context.",
        targetElements = listOf("bpmn:Lane"),
        errorMessages = mapOf(
            "default" to "Lane must have a business role or performer name",
        ),
        check = PoolLabelCheckConfig(mode = PoolLabelMode.LANE_LABELS_BUSINESS_ROLES_PERFORMERS),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
    )
}
