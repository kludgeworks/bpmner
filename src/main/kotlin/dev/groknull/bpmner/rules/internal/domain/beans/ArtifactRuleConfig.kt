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
import dev.groknull.bpmner.rules.internal.domain.primitives.AssociationDirection
import dev.groknull.bpmner.rules.internal.domain.primitives.PresenceCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.RequiredAssociationCheckConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(name = ["bpmner.rules.source"], havingValue = "kotlin")
@Suppress("MaxLineLength")
internal class ArtifactRuleConfig {
    @Bean
    fun artifactGroupUsage(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Group Usage",
        category = RuleCategory.Artifact,
        intent = "Keep BPMN groups as visual, non-semantic containers.",
        forModellers = "Use Group to visually group related elements; it does not affect process logic.",
        forAI = "Treat groups as visual, non-semantic containers. Do not infer control flow, data flow, ownership, or membership semantics from a group.",
        targetElements = listOf("bpmn:Group"),
        errorMessages = mapOf(
            "default" to "Groups are visual containers and require modelling context",
        ),
        check = PresenceCheckConfig,
        nlp = nlp,
        severity = RuleSeverity.INFO,
    )

    @Bean
    fun artifactTextAnnotation(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Text Annotation Usage",
        category = RuleCategory.Artifact,
        intent = "Ensure text annotations are explicitly connected to the element they clarify.",
        forModellers = "Use Text Annotation to document clarifications or extra details, and attach it to its target with an association.",
        forAI = "Require text annotations to have at least one association; loop and multi-instance specificity remains covered by activity and association rules.",
        targetElements = listOf("bpmn:TextAnnotation"),
        errorMessages = mapOf(
            "default" to "Text annotation must be linked to a BPMN element with an association",
        ),
        check = RequiredAssociationCheckConfig(
            association = "bpmn:Association",
            direction = AssociationDirection.INBOUND,
        ),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
    )
}
