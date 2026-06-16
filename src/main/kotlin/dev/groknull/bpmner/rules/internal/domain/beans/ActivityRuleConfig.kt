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
import dev.groknull.bpmner.rules.BpmnerLintConfig
import dev.groknull.bpmner.rules.internal.domain.compositeRule
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.rules.internal.domain.primitiveRule
import dev.groknull.bpmner.rules.internal.domain.primitives.NlpPosTag
import dev.groknull.bpmner.rules.internal.domain.primitives.PartOfSpeechCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PartOfSpeechMode
import dev.groknull.bpmner.rules.internal.domain.primitives.PropertyPatternCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.VocabularyCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.VocabularyMode
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@Suppress("MaxLineLength")
internal class ActivityRuleConfig {
    companion object {
        // DSL string literals shared across multiple @Bean methods in this class.
        private const val BPMN_TASK = "bpmn:Task"
        private const val BPMN_SUB_PROCESS = "bpmn:SubProcess"
        private const val BPMN_CALL_ACTIVITY = "bpmn:CallActivity"
        private val TASK_SUBPROCESS_CALLACTIVITY = listOf(BPMN_TASK, BPMN_SUB_PROCESS, BPMN_CALL_ACTIVITY)
        private val TASK_SUBPROCESS = listOf(BPMN_TASK, BPMN_SUB_PROCESS)
    }

    @Bean
    fun actActivityLabelCapitalization(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Activity Label Capitalization",
        category = RuleCategory.Activity,
        intent = "Keep activity labels in readable sentence case.",
        forModellers = "Capitalize the first word of an activity label and keep later words lowercase unless they are acronyms or proper nouns.",
        forAI = "Detect activity, subprocess, and call activity labels that start lowercase or use title case after the first word.",
        targetElements = TASK_SUBPROCESS_CALLACTIVITY,
        errorMessages = mapOf(
            "firstWord" to "Activity label should start with a capitalized first word",
            "sentenceCase" to "Activity label should use sentence case after the first word (except acronyms/proper nouns)",
            "default" to "Activity label should use sentence case",
        ),
        check = PropertyPatternCheckConfig(
            property = "name",
            pattern = "^[A-Z].*",
            patternDescription = "label should start with an uppercase letter",
        ),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
        repair = RepairMetadata(
            kind = RepairKind.LOCAL_MODEL_FIX,
            safety = RepairSafety.SAFE_AUTOMATIC,
            handler = "fixSentenceCase",
        ),
    )

    @Bean
    fun actDiscouragedBusinessVerbs(nlp: BpmnNlp, lintConfig: BpmnerLintConfig): BpmnRule = primitiveRule(
        name = "Discouraged Business Verbs",
        category = RuleCategory.Activity,
        intent = "Avoid generic activity verbs that hide the real business action.",
        forModellers = "Replace vague leading verbs with a more specific business verb.",
        forAI = "Detect activity labels whose first word is on the discouraged generic verb list.",
        targetElements = TASK_SUBPROCESS_CALLACTIVITY,
        errorMessages = mapOf(
            "default" to "Activity label starts with a discouraged generic verb; prefer a more specific business verb",
        ),
        check = VocabularyCheckConfig(
            property = "name",
            mode = VocabularyMode.FORBID_LEADING,
            words = lintConfig.discouragedLeadingVerbs,
        ),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
    )

    @Bean
    fun actLoopTaskAnnotation(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Loop Task Annotation",
        category = RuleCategory.Activity,
        intent = "Ensure loop activities document the condition that stops repetition.",
        forModellers = "Attach a text annotation to each loop task or subprocess that explains the loop condition, for example Loop until the condition is met.",
        forAI = "Detect standard loop activities without an associated annotation whose text contains loop intent and a condition such as until, while, unless, or till.",
        targetElements = TASK_SUBPROCESS,
        errorMessages = mapOf(
            "default" to "Loop activity's annotation must express the loop condition with until, while, unless, or till",
        ),
        check = VocabularyCheckConfig(
            property = "annotationText",
            mode = VocabularyMode.REQUIRE,
            words = listOf("until", "while", "unless", "till"),
            appliesWhenProperty = "standardLoopCharacteristics",
        ),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
    )

    @Bean
    fun actMiTaskAnnotation(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "MI Task Annotation",
        category = RuleCategory.Activity,
        intent = "Ensure multi-instance activities document the set of items being iterated.",
        forModellers = "Attach a text annotation to each multi-instance task or subprocess that explains the item set, for example For each passenger.",
        forAI = "Detect multi-instance activities without an associated annotation containing iteration-set wording such as each, every, or per.",
        targetElements = TASK_SUBPROCESS,
        errorMessages = mapOf(
            "default" to "Multi-instance activity's annotation must name the item set with each, every, or per",
        ),
        check = VocabularyCheckConfig(
            property = "annotationText",
            mode = VocabularyMode.REQUIRE,
            words = listOf("each", "every", "per"),
            appliesWhenProperty = "multiInstanceLoopCharacteristics",
        ),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
    )

    @Bean
    fun actVerbObjectName(nlp: BpmnNlp): BpmnRule = compositeRule(
        name = "Verb Object Name",
        category = RuleCategory.Activity,
        intent = "Make activity labels action-oriented and specific.",
        forModellers = "Name activities with a business verb followed by the object being acted on.",
        forAI = "Detect activity labels that do not start with a verb or that contain fewer than two words.",
        targetElements = TASK_SUBPROCESS_CALLACTIVITY,
        errorMessages = mapOf(
            "tooShort" to "Activity name should follow Verb + Object (at least two words)",
            "missingVerb" to "Activity name should start with a business verb",
            "default" to "Activity name should follow Verb + Object",
        ),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
    ) {
        sub(
            "tooShort",
            PropertyPatternCheckConfig(
                property = "name",
                pattern = "^\\S+\\s+\\S+.*",
                patternDescription = "label should be at least two words (Verb + Object)",
            ),
        )
        sub(
            "missingVerb",
            PartOfSpeechCheckConfig(
                property = "name",
                mode = PartOfSpeechMode.LEADING_MUST_BE,
                posClass = NlpPosTag.VERB,
            ),
        )
    }
}
