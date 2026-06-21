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
import dev.groknull.bpmner.ruleset.internal.domain.primitives.PropertyPatternCheckConfig
import dev.groknull.bpmner.ruleset.internal.domain.primitives.VocabularyCheckConfig
import dev.groknull.bpmner.ruleset.internal.domain.primitives.VocabularyMode
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@Suppress("MaxLineLength")
internal class NameRuleConfig {
    companion object {
        // DSL string literals shared across all three @Bean methods in this class.
        private val NAMED_ELEMENTS = listOf(
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
        )
    }

    @Bean
    fun nameBusinessMeaningfulLabel(nlp: BpmnNlp, lintConfig: BpmnerLintConfig): BpmnRule = primitiveRule(
        name = "Business Meaningful Label",
        category = RuleCategory.Name,
        intent = "Encourage business-readable labels over technical identifiers.",
        forModellers = "Choose names that are meaningful to business stakeholders and avoid technical shorthand, code names, and implementation identifiers.",
        forAI = "Detect labels containing technical patterns such as underscores, slash paths, alphanumeric codes, or configured technical tokens.",
        targetElements = NAMED_ELEMENTS,
        errorMessages = mapOf(
            "default" to "Label appears technical/cryptic; prefer business-meaningful wording",
        ),
        check = PropertyPatternCheckConfig(
            property = "name",
            pattern = "^(?!.*[_/])(?!.*[a-z][A-Z])(?!.*\\d).+$",
            patternDescription = "labels should be business-readable (no underscores, slash paths, camelCase, or digits)",
            forbiddenVocabulary = lintConfig.technicalTokens,
        ),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
        repair = RepairMetadata(
            kind = RepairKind.LOCAL_MODEL_FIX,
            safety = RepairSafety.SAFE_AUTOMATIC,
            handler = "expandAbbreviations",
        ),
    )

    @Bean
    fun nameNoElementTypeWords(nlp: BpmnNlp, lintConfig: BpmnerLintConfig): BpmnRule = primitiveRule(
        name = "No Element Type Words",
        category = RuleCategory.Name,
        intent = "Avoid redundant BPMN element type words in labels.",
        forModellers = "Do not include words such as activity, process, or event in element names because the BPMN shape already indicates the type.",
        forAI = "Detect named BPMN elements whose labels include redundant element type words.",
        targetElements = NAMED_ELEMENTS,
        errorMessages = mapOf(
            "default" to "Element name must not include its BPMN element type",
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

    @Bean
    fun nameUncommonAbbreviations(nlp: BpmnNlp, lintConfig: BpmnerLintConfig): BpmnRule = primitiveRule(
        name = "Uncommon Abbreviations",
        category = RuleCategory.Name,
        intent = "Reduce ambiguity from obscure abbreviations in BPMN labels.",
        forModellers = "Avoid uncommon abbreviations in labels, or explain them with an annotation or glossary.",
        forAI = "Detect uppercase abbreviations that are not on the common acronym allow-list and ask for a clearer label or explanation.",
        targetElements = NAMED_ELEMENTS,
        errorMessages = mapOf(
            "default" to "Avoid uncommon abbreviations in labels or explain them via annotation/glossary",
        ),
        check = PropertyPatternCheckConfig(
            property = "name",
            pattern = "^(?!.*\\b[A-Z]{2,}\\b).+$",
            patternDescription = "labels should not contain uncommon uppercase abbreviations",
            allowedVocabulary = lintConfig.allowedAcronyms,
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
