/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.rules.BpmnerLintConfig
import dev.groknull.bpmner.rules.internal.domain.DeterministicRule
import dev.groknull.bpmner.rules.internal.domain.primitives.ElementConstraintCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PropertyPatternCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.VocabularyCheckConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@Suppress("MaxLineLength")
internal class BeanRuleRegistryConstructionTest {
    @Test
    @Suppress("LongMethod")
    fun `kotlin bean registry loads active rules with unique ids`() {
        bpmnerKotlinRuleContext().use { context ->
            val ruleRegistry = context.getBean(BeanRuleRegistry::class.java)
            val activeRules = ruleRegistry.activeRules()
            val activeIds = activeRules.map { it.id }
            val llmSpecs = ruleRegistry.llmRuleSpecs()
            val llmIds = llmSpecs.map { it.metadata.id }

            // Executable rules: 40 bean + 7 compiled = 47
            assertThat(activeRules).hasSize(47)
            assertThat(activeIds).doesNotHaveDuplicates()

            // LLM rule specs: 2 metadata-only rules (excluded from activeRules)
            assertThat(llmSpecs).hasSize(2)
            assertThat(llmIds).contains(
                "gen-business-clarity-over-technical-detail",
                "gtw-exclusive-inclusive-parallel-semantics",
            )

            // LLM specs are resolvable by id but are excluded from activeRules().
            for (llmId in llmIds) {
                assertThat(ruleRegistry.ruleByIdOrAlias(llmId)).isNotNull
                assertThat(activeIds).doesNotContain(llmId)
            }

            // All 40 active Pkl-derived bean ids (excluding 9 deferred rules).
            // Includes the 7 compiled Kotlin rules in the total count.
            assertThat(activeIds).contains(
                // Activity (5)
                "act-activity-label-capitalization",
                "act-discouraged-business-verbs",
                "act-loop-task-annotation",
                "act-mi-task-annotation",
                "act-verb-object-name",
                // Artifact (2)
                "art-group-usage",
                "art-text-annotation-usage",
                // Association (1)
                "assoc-required-annotation-association",
                // Data (1)
                "data-no-type-words-in-data-name",
                // Event (10)
                "evt-boundary-event-constraints",
                "evt-error-end-boundary-pair",
                "evt-event-state-name",
                "evt-event-state-pattern",
                "evt-intermediate-event-not-action",
                "evt-link-event-pairing",
                "evt-message-start-has-message-flow",
                "evt-start-no-incoming",
                "evt-timer-start-events-block-until-time",
                // Flow (2)
                "flow-diverging-flow-outcome-label",
                "flow-sequence-flow-within-pool",
                // Gateway (9)
                "gtw-converging-gateway-unnamed",
                "gtw-diverging-flow-names",
                "gtw-diverging-gateway-question",
                "gtw-event-based-direct-events",
                "gtw-fake-join",
                "gtw-gateway-no-work-label",
                "gtw-no-gateway-join-fork",
                "gtw-superfluous-gateway",
                // General (2)
                "gen-bpmn-subset",
                "gen-no-duplicate-diagrams",
                // Lane (2)
                "lane-actor-artifact-usage",
                "lane-lane-labels-business-roles-performers",
                // Message (2)
                "msg-message-flow-across-pools",
                "msg-message-flow-name-pattern",
                // Name (3)
                "name-business-meaningful-label",
                "name-no-element-type-words",
                "name-uncommon-abbreviations",
                // Pool (3)
                "pool-black-box-pool-named-by-external-entity-or-process",
                "pool-child-diagrams-keep-pool-process-name",
                "pool-white-box-pool-named-by-process",
            )

            // The 9 deferred/no-primitive rules must be absent.
            assertThat(activeIds).doesNotContain(
                "art-information-item-vs-application-component",
                "data-envelope-icon-usage",
                "def-business-process-element-usage",
                "evt-receive-task-vs-intermediate-message-event",
                "evt-send-task-vs-throwing-message-event",
                "evt-signal-events-broadcast-only-when-needed",
                "act-task-vs-subprocess-vs-call-activity",
                // LLM specs are deferred but not executable, so they're also absent from activeIds
                "gen-business-clarity-over-technical-detail",
                "gtw-exclusive-inclusive-parallel-semantics",
            )
        }
    }

    @Test
    fun `convention config drives relevant rule check configs`() {
        val lintConfig = BpmnerLintConfig(
            discouragedLeadingVerbs = listOf("coordinate"),
            elementTypeWords = listOf("step"),
            allowedAcronyms = listOf("BPMN", "VIP"),
            technicalTokens = listOf("impl"),
            discouragedBpmnTypes = listOf("bpmn:Transaction"),
        )

        bpmnerKotlinRuleContext(lintConfig).use { context ->
            val ruleRegistry = context.getBean(BeanRuleRegistry::class.java)

            assertThat(vocabularyWords(ruleRegistry, "act-discouraged-business-verbs"))
                .containsExactly("coordinate")
            assertThat(vocabularyWords(ruleRegistry, "name-no-element-type-words")).containsExactly("step")
            assertThat(vocabularyWords(ruleRegistry, "data-no-type-words-in-data-name")).containsExactly("step")
            assertThat(propertyPattern(ruleRegistry, "name-business-meaningful-label").forbiddenVocabulary)
                .containsExactly("impl")
            assertThat(propertyPattern(ruleRegistry, "name-uncommon-abbreviations").allowedVocabulary)
                .containsExactly("BPMN", "VIP")
            assertThat(ruleRegistry.ruleById("gen-bpmn-subset")!!.metadata.targetElements)
                .containsExactly("bpmn:Transaction")
            assertThat(elementConstraint(ruleRegistry, "gen-bpmn-subset").constraints).containsEntry("allowed", "")
        }
    }

    private fun vocabularyWords(ruleRegistry: BeanRuleRegistry, ruleId: String): List<String> = (ruleRegistry.ruleById(ruleId) as DeterministicRule).config.let { it as VocabularyCheckConfig }.words

    private fun propertyPattern(ruleRegistry: BeanRuleRegistry, ruleId: String): PropertyPatternCheckConfig = (ruleRegistry.ruleById(ruleId) as DeterministicRule).config.let { it as PropertyPatternCheckConfig }

    private fun elementConstraint(ruleRegistry: BeanRuleRegistry, ruleId: String): ElementConstraintCheckConfig = (ruleRegistry.ruleById(ruleId) as DeterministicRule).config.let { it as ElementConstraintCheckConfig }
}
