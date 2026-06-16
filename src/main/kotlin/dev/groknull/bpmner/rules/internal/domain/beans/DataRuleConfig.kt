/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairMetadata
import dev.groknull.bpmner.api.RepairSafety
import dev.groknull.bpmner.api.RuleCategory
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.rules.internal.domain.primitiveRule
import dev.groknull.bpmner.rules.internal.domain.primitives.VocabularyCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.VocabularyMode
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@Suppress("MaxLineLength")
internal class DataRuleConfig {
    @Bean
    fun dataNoTypeWordsInDataName(nlp: BpmnNlp): BpmnRule = primitiveRule(
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
            words = listOf("activity", "process", "event"),
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
