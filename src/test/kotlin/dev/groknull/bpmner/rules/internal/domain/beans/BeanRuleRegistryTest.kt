/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RuleCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class BeanRuleRegistryTest {
    @Test
    fun `every rule has a non-blank intent`() {
        bpmnerKotlinRuleContext().use { context ->
            val registry = context.getBean(BeanRuleRegistry::class.java)
            val allRules = registry.activeRules() + registry.llmRuleSpecs()

            for (rule in allRules) {
                assertThat(rule.metadata.intent).describedAs("intent for rule ${rule.id}").isNotBlank()
            }
        }
    }

    @Test
    fun `every rule defines at least one error message`() {
        bpmnerKotlinRuleContext().use { context ->
            val registry = context.getBean(BeanRuleRegistry::class.java)
            val allRules = registry.activeRules() + registry.llmRuleSpecs()

            for (rule in allRules) {
                assertThat(rule.metadata.errorMessages).describedAs("errorMessages for rule ${rule.id}").isNotEmpty()
            }
        }
    }

    @Test
    fun `every rule has a valid category`() {
        bpmnerKotlinRuleContext().use { context ->
            val registry = context.getBean(BeanRuleRegistry::class.java)
            val allRules = registry.activeRules() + registry.llmRuleSpecs()

            for (rule in allRules) {
                assertThat(RuleCategory.entries).contains(rule.metadata.category)
            }
        }
    }

    @Test
    fun `LLM rules carry no LOCAL_MODEL_FIX repair`() {
        bpmnerKotlinRuleContext().use { context ->
            val registry = context.getBean(BeanRuleRegistry::class.java)
            val llmSpecs = registry.llmRuleSpecs()

            for (spec in llmSpecs) {
                assertThat(spec.metadata.repair.kind).describedAs("repair.kind for LLM rule ${spec.id}")
                    .isNotEqualTo(RepairKind.LOCAL_MODEL_FIX)
            }
        }
    }

    @Test
    fun `rule ids are unique`() {
        bpmnerKotlinRuleContext().use { context ->
            val registry = context.getBean(BeanRuleRegistry::class.java)
            val allRules = registry.activeRules() + registry.llmRuleSpecs()
            val allIds = allRules.map { it.id }

            assertThat(allIds).doesNotHaveDuplicates()
        }
    }

    @Test
    fun `registry emits at least one rule`() {
        bpmnerKotlinRuleContext().use { context ->
            val registry = context.getBean(BeanRuleRegistry::class.java)
            val activeRules = registry.activeRules()

            assertThat(activeRules).isNotEmpty()
        }
    }

    @Test
    fun `LLM specs are resolvable by id`() {
        bpmnerKotlinRuleContext().use { context ->
            val registry = context.getBean(BeanRuleRegistry::class.java)
            val llmSpecs = registry.llmRuleSpecs()

            for (spec in llmSpecs) {
                assertThat(registry.ruleByIdOrAlias(spec.id)).isNotNull
            }
        }
    }
}
