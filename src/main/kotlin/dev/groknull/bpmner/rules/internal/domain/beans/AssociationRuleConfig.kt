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
import dev.groknull.bpmner.rules.internal.domain.primitives.RequiredAssociationCheckConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class AssociationRuleConfig {
    @Bean
    fun associationRequiredAnnotation(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Required Annotation Association",
        category = RuleCategory.Association,
        intent = "Require explicit association links from loop and multi-instance activities to their explanatory annotations.",
        forModellers = "Use an association to link required text annotations to loop or multi-instance tasks and subprocesses.",
        forAI = "Detect loop or multi-instance activities that do not have any associated text annotation.",
        targetElements = listOf("bpmn:Task", "bpmn:SubProcess"),
        errorMessages = mapOf(
            "default" to "Loop or multi-instance activity must be linked to a text annotation via association",
        ),
        check = RequiredAssociationCheckConfig(
            association = "bpmn:Association",
            targetTypes = listOf("bpmn:TextAnnotation"),
            appliesWhenProperty = "multiInstanceLoopCharacteristics",
        ),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
    )
}
