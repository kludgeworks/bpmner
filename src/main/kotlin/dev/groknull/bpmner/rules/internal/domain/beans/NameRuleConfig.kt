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
import dev.groknull.bpmner.rules.internal.domain.primitives.PropertyPatternCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.VocabularyCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.VocabularyMode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(name = ["bpmner.rules.source"], havingValue = "kotlin")
@Suppress("MaxLineLength")
internal class NameRuleConfig {
    @Bean
    fun nameNoElementTypeWords(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "No Element Type Words",
        category = RuleCategory.Name,
        intent = "Avoid redundant BPMN element type words in labels.",
        forModellers = "Do not include words such as activity, process, or event in element names because the BPMN shape already indicates the type.",
        forAI = "Detect named BPMN elements whose labels include redundant element type words.",
        targetElements = listOf(
            "bpmn:Task",
            "bpmn:SubProcess",
            "bpmn:CallActivity",
            "bpmn:StartEvent",
            "bpmn:IntermediateCatchEvent",
            "bpmn:IntermediateThrowEvent",
            "bpmn:EndEvent",
            "bpmn:ExclusiveGateway",
            "bpmn:InclusiveGateway",
            "bpmn:ParallelGateway",
            "bpmn:ComplexGateway",
            "bpmn:DataObjectReference",
            "bpmn:DataStoreReference",
        ),
        errorMessages = mapOf(
            "default" to "Element name must not include its BPMN element type",
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

    @Bean
    fun nameNoTypeWordsInDataName(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "No Type Words In Data Name",
        category = RuleCategory.Name,
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

    @Bean
    fun nameUncommonAbbreviations(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Uncommon Abbreviations",
        category = RuleCategory.Name,
        intent = "Reduce ambiguity from obscure abbreviations in BPMN labels.",
        forModellers = "Avoid uncommon abbreviations in labels, or explain them with an annotation or glossary.",
        forAI = "Detect uppercase abbreviations that are not on the common acronym allow-list and ask for a clearer label or explanation.",
        targetElements = listOf(
            "bpmn:Task",
            "bpmn:SubProcess",
            "bpmn:CallActivity",
            "bpmn:StartEvent",
            "bpmn:IntermediateCatchEvent",
            "bpmn:IntermediateThrowEvent",
            "bpmn:EndEvent",
            "bpmn:ExclusiveGateway",
            "bpmn:InclusiveGateway",
            "bpmn:ParallelGateway",
            "bpmn:ComplexGateway",
            "bpmn:DataObjectReference",
            "bpmn:DataStoreReference",
        ),
        errorMessages = mapOf(
            "default" to "Avoid uncommon abbreviations in labels or explain them via annotation/glossary",
        ),
        check = PropertyPatternCheckConfig(
            property = "name",
            pattern = "^(?!.*\\b[A-Z]{2,}\\b).+$",
            patternDescription = "labels should not contain uncommon uppercase abbreviations",
            allowedVocabulary = listOf("BPMN", "ACME", "SLA", "API", "IT"),
        ),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
        repair = RepairMetadata(
            kind = RepairKind.LOCAL_MODEL_FIX,
            safety = RepairSafety.SAFE_AUTOMATIC,
            handler = "expandAbbreviations",
            replacementMap = mapOf(
                "REQ" to "request",
                "RESP" to "response",
                "AUTH" to "authentication",
                "CFG" to "configuration",
                "MSG" to "message",
                "DOC" to "document",
                "ITBL" to "itinerary block",
            ),
        ),
    )
}
