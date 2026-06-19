/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.rules.RuleRegistry
import dev.groknull.bpmner.rules.RulesTestFixtures
import dev.groknull.bpmner.validation.internal.domain.LlmValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * Regression gate: proves [LlmValidator.getLlmRuleGuidance] produces non-empty guidance text
 * and includes the known LLM rule ids and their key metadata fields after the #380 cutover.
 *
 * Plan exit gate 7 + arch §162-167, §243-255: the guidance text must remain populated and
 * structurally correct when `llmRuleSpecs()` is the source instead of the removed
 * `checkPrimitive`-based `isLlmJudged` filter.
 *
 * The rule context is obtained via [RulesTestFixtures.fullBeanRuleContext] — the published
 * `rules` root test fixture — so this test does not reach into `rules.internal.*` directly
 * (S5 — ARCHITECTURE §5 S5, §1.5).
 */
@Suppress("DEPRECATION")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class LlmValidatorGuidanceTest {
    private lateinit var beanContext: AnnotationConfigApplicationContext
    private lateinit var validator: LlmValidator

    @BeforeAll
    fun setUp() {
        // Use the rules module's published test fixture to build the Spring context,
        // avoiding direct imports from rules.internal (S5 — ARCHITECTURE §5 S5, §1.5).
        beanContext = RulesTestFixtures.fullBeanRuleContext()
        val registry = beanContext.getBean(RuleRegistry::class.java)
        validator = LlmValidator(registry)
    }

    @AfterAll
    fun tearDown() {
        if (::beanContext.isInitialized) beanContext.close()
    }

    @Test
    fun `getLlmRuleGuidance returns non-empty text containing both LLM rule ids`() {
        val guidance = validator.getLlmRuleGuidance()

        assertThat(guidance)
            .describedAs("Guidance text must be non-empty when LLM specs are registered")
            .isNotBlank()

        assertThat(guidance).contains("gen-business-clarity-over-technical-detail")
        assertThat(guidance).contains("gtw-exclusive-inclusive-parallel-semantics")
    }

    @Test
    fun `getLlmRuleGuidance contains required structural sections for each rule`() {
        val guidance = validator.getLlmRuleGuidance()

        // Heading section with id and name
        assertThat(guidance).contains("gen-business-clarity-over-technical-detail: Business Clarity Over Technical Detail")
        assertThat(guidance).contains("gtw-exclusive-inclusive-parallel-semantics: Exclusive Inclusive Parallel Semantics")

        // Intent line per rule (partial match for long lines)
        assertThat(guidance).contains("Intent: Keep BPMN diagrams focused on business behavior")
        assertThat(guidance).contains("Intent: Keep gateway type choices aligned with BPMN token semantics.")

        // Guidance (forAI) line per rule — partial match for long lines
        assertThat(guidance).contains("Guidance: Review labels and structure for business readability.")
        assertThat(guidance).contains("Guidance: Enforce deterministic parallel-gateway structure from XML.")
    }

    @Test
    fun `getLlmRuleGuidance returns empty string when registry has no LLM specs`() {
        // Use an empty registry from the rules module's published test fixture.
        val emptyRegistry = RulesTestFixtures.emptyRegistry()
        val emptyValidator = LlmValidator(emptyRegistry)

        assertThat(emptyValidator.getLlmRuleGuidance()).isEmpty()
    }
}
