/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.beans

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * Test coverage for [BeanRuleRegistry] against the live Spring bean registry.
 *
 * This class ports the *reachable* Pkl-native facts from `SchemaTest.pkl` (intent non-blank),
 * `InvalidAutofixLLM.pkl` (LLM rules must not carry local repair kind), and `RulesIndexTest.pkl`
 * (non-empty ruleset).
 *
 * **Retired facts** (per plan #384, stage-gate 8 "JUnit **or** explicitly retired with rationale"):
 * - `RuleMetadata.init` `require(errorMessages.isNotEmpty())` - construction-enforced; covered by
 *   [BpmnRuleContractTest.kt:134] (`RuleMetadata rejects empty error messages at construction time`)
 * - `BeanRuleRegistry.init` uniqueness checks - construction-enforced; covered by
 *   [BeanRuleRegistryConstructionTest.kt:24,34-37]
 * - `BeanRuleRegistry.init` LLM resolvability - unconditionally non-null by construction (union
 *   `byId` map); covered by [BeanRuleRegistryConstructionTest.kt:34-37]
 * - `RuleCategory` typed enum - compile-time tautology; cannot fail
 * - `repair.handler` coupling for `LOCAL_MODEL_FIX` - a production invariant outside #384's scope;
 *   see plan §Non-goals
 *
 * @see BeanRuleRegistryConstructionTest
 * @see RuleSourceParityTest
 * @see BpmnRuleContractTest
 */
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
    fun `LLM rules carry no local repair kind`() {
        val llmSpecs = registry.llmRuleSpecs()

        for (spec in llmSpecs) {
            val repairKind = spec.metadata.repair.kind
            assertThat(repairKind.isLocal()).describedAs("repair.kind.isLocal() for LLM rule ${spec.id}").isFalse()
        }
    }

    @Test
    fun `registry emits at least one rule`() {
        val activeRules = registry.activeRules()

        assertThat(activeRules).isNotEmpty()
    }
}
