/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RuleCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.AnnotationConfigApplicationContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class BeanRuleRegistryTest {
    private lateinit var registry: BeanRuleRegistry
    private lateinit var context: AnnotationConfigApplicationContext

    @BeforeAll
    fun setUp() {
        context = bpmnerKotlinRuleContext()
        registry = context.getBean(BeanRuleRegistry::class.java)
    }

    @AfterAll
    fun tearDown() {
        if (::context.isInitialized) {
            context.close()
        }
    }

    @Test
    fun `every rule has a non-blank intent`() {
        val allRules = registry.activeRules() + registry.llmRuleSpecs()

        for (rule in allRules) {
            assertThat(rule.metadata.intent).describedAs("intent for rule ${rule.id}").isNotBlank()
        }
    }

    @Test
    fun `every rule defines at least one error message`() {
        val allRules = registry.activeRules() + registry.llmRuleSpecs()

        for (rule in allRules) {
            assertThat(rule.metadata.errorMessages).describedAs("errorMessages for rule ${rule.id}").isNotEmpty()
        }
    }

    @Test
    fun `every rule has a valid category`() {
        val allRules = registry.activeRules() + registry.llmRuleSpecs()

        for (rule in allRules) {
            assertThat(RuleCategory.entries).contains(rule.metadata.category)
        }
    }

    @Test
    fun `LLM rules carry no LOCAL_MODEL_FIX repair`() {
        val llmSpecs = registry.llmRuleSpecs()

        for (spec in llmSpecs) {
            assertThat(spec.metadata.repair.kind).describedAs("repair.kind for LLM rule ${spec.id}")
                .isNotEqualTo(RepairKind.LOCAL_MODEL_FIX)
        }
    }

    @Test
    fun `rule ids are unique`() {
        val allRules = registry.activeRules() + registry.llmRuleSpecs()
        val allIds = allRules.map { it.id }

        assertThat(allIds).doesNotHaveDuplicates()
    }

    @Test
    fun `registry emits at least one rule`() {
        val activeRules = registry.activeRules()

        assertThat(activeRules).isNotEmpty()
    }

    @Test
    fun `LLM specs are resolvable by id`() {
        val llmSpecs = registry.llmRuleSpecs()

        for (spec in llmSpecs) {
            assertThat(registry.ruleByIdOrAlias(spec.id)).isNotNull
        }
    }
}
