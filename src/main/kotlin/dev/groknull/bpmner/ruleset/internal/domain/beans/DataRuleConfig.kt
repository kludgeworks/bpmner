/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.beans

import dev.groknull.bpmner.bpmn.BpmnRule
import dev.groknull.bpmner.bpmn.RepairKind
import dev.groknull.bpmner.bpmn.RepairMetadata
import dev.groknull.bpmner.bpmn.RepairSafety
import dev.groknull.bpmner.bpmn.RuleCategory
import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.ruleset.BpmnerLintConfig
import dev.groknull.bpmner.ruleset.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.ruleset.internal.domain.primitiveRule
import dev.groknull.bpmner.ruleset.internal.domain.primitives.VocabularyCheckConfig
import dev.groknull.bpmner.ruleset.internal.domain.primitives.VocabularyMode
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@Suppress("MaxLineLength")
internal class DataRuleConfig {
    @Bean
    fun dataNoTypeWordsInDataName(nlp: BpmnNlp, lintConfig: BpmnerLintConfig): BpmnRule = primitiveRule(
        name = "No Type Words In Data Name",
        category = RuleCategory.Data,
        intent = "Keep data element names business-oriented and noun-based.",
        forModellers = "Name data objects and data stores with business noun phrases, without redundant BPMN type words such as activity, process, or event.",
        forAI = "Detect data object and data store names that include discouraged BPMN type words.",
        targetElements = listOf("bpmn:DataObject", "bpmn:DataStore"),
        errorMessages = mapOf(
            "default" to "Data element name must be a business noun phrase, not an element-type label",
        ),
        check = VocabularyCheckConfig(
            property = "name",
            mode = VocabularyMode.FORBID,
            words = lintConfig.elementTypeWords,
        ),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(
            kind = RepairKind.LOCAL_MODEL_FIX,
            safety = RepairSafety.SAFE_AUTOMATIC,
            handler = "stripTypeWords",
        ),
    )
}
