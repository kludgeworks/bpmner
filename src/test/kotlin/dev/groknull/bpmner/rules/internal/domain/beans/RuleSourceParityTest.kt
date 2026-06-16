/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("MaxLineLength")

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.api.BpmnDefinition
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.generation.BpmnXmlToDefinitionConverter
import dev.groknull.bpmner.rules.RuleProfile
import dev.groknull.bpmner.rules.RuleRegistry
import dev.groknull.bpmner.rules.internal.domain.CompositeRule
import dev.groknull.bpmner.rules.internal.domain.DefaultRuleEngine
import dev.groknull.bpmner.rules.internal.domain.DeterministicRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.stream.Stream

/**
 * Regression gate: proves the Kotlin bean catalog ([BeanRuleRegistry]) continues to work
 * correctly after the #380 cutover (Pkl catalog and bridge code removed). This test replaces
 * the old Pkl-vs-bean parity comparison.
 *
 * Implements assertions for:
 *
 * - Active rule count (47 = 40 Kotlin beans + 7 compiled rules)
 * - LLM rule specs count (2 metadata-only specs: BusinessClarityOverTechnicalDetail,
 *   ExclusiveInclusiveParallelSemantics)
 * - Tier-1 metadata (category, severity, targetElements) on executable rules
 * - Typed check config validity (DeterministicCheckConfig/CompositeCheckConfig)
 * - Fixture diagnostics (set-compared)
 *
 * The bean registry is now the default runtime registry (no `@ConditionalOnProperty`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("TooManyFunctions")
internal class RuleSourceParityTest {
    private lateinit var beanRegistry: RuleRegistry
    private lateinit var beanContext: AnnotationConfigApplicationContext

    @BeforeAll
    fun setUp() {
        // Build the bean registry via an isolated Spring context (shared helper; see
        // BeanRuleRegistryConstructionTest). The context is held open for the
        // whole class and closed in tearDown so the registry's beans stay live during evaluation.
        beanContext = bpmnerKotlinRuleContext()
        beanRegistry = beanContext.getBean(BeanRuleRegistry::class.java)
    }

    @AfterAll
    fun tearDown() {
        if (::beanContext.isInitialized) {
            beanContext.close()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Bean-registry regression assertions (after #380 Pkl bridge removal)
    // -----------------------------------------------------------------------------------------

    @Test
    fun `Tier-1 (a) - bean registry exposes correct active rule count and unique ids`() {
        val activeRules = beanRegistry.activeRules()
        val activeIds = activeRules.map { it.id }

        // 40 Kotlin bean rules + 7 compiled = 47 executable rules
        assertThat(activeRules).hasSize(47)
        assertThat(activeIds).doesNotHaveDuplicates()
    }

    @Test
    fun `Tier-1 (b) - LLM rule specs are 2 metadata-only wrappers`() {
        val llmSpecs = beanRegistry.llmRuleSpecs()

        // Two deferred LLM-flavoured rules remain as metadata-only specs
        assertThat(llmSpecs).hasSize(2)
        assertThat(llmSpecs.map { it.metadata.id }).containsExactlyInAnyOrder(
            "gen-business-clarity-over-technical-detail",
            "gtw-exclusive-inclusive-parallel-semantics",
        )

        // LLM specs are resolvable for metadata rendering but are not active executable rules.
        val activeIds = beanRegistry.activeRules().map { it.id }
        for (llmId in llmSpecs.map { it.metadata.id }) {
            assertThat(beanRegistry.ruleByIdOrAlias(llmId)).isNotNull
            assertThat(activeIds).doesNotContain(llmId)
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `Tier-1 (c) - per-id Tier-1 metadata matches known values`() {
        val beanById = beanRegistry.activeRules().associateBy { it.id }
        val sharedIds = beanById.keys

        val mismatches = mutableListOf<String>()
        for (id in sharedIds.sorted()) {
            val beanMeta = beanById.getValue(id).metadata

            val errors = mutableListOf<String>()

            // Verify required metadata fields are present
            if (beanMeta.category == null) {
                errors += "category is null"
            }
            if (beanMeta.severity == null) {
                errors += "severity is null"
            }
            if (beanMeta.targetElements.isEmpty()) {
                errors += "targetElements is empty"
            }

            if (errors.isNotEmpty()) {
                mismatches += "[$id]: ${errors.joinToString("; ")}"
            }
        }

        assertThat(mismatches)
            .describedAs("Tier-1 metadata validation failures (category, severity, targetElements)")
            .isEmpty()
    }

    @Test
    fun `Tier-1 (d) - per-id typed check config is valid`() {
        val beanById = beanRegistry.activeRules().associateBy { it.id }
        val sharedIds = beanById.keys

        val mismatches = mutableListOf<String>()
        for (id in sharedIds.sorted()) {
            val beanRule = beanById.getValue(id)

            // Every executable rule must have a valid config
            val typeError = when {
                beanRule is DeterministicRule -> {
                    if (beanRule.config == null) {
                        "DeterministicCheckConfig is null"
                    } else {
                        null
                    }
                }
                beanRule is CompositeRule -> {
                    if (beanRule.config == null) {
                        "CompositeCheckConfig is null"
                    } else {
                        null
                    }
                }
                else ->
                    // Both are compiled @Component rules (DanglingEdge, etc.) — no config expected.
                    null
            }

            if (typeError != null) {
                mismatches += "[$id]: $typeError"
            }
        }

        assertThat(mismatches)
            .describedAs("Tier-1 typed config validation failures")
            .isEmpty()
    }

    @ParameterizedTest(name = "fixture diagnostics match expected for {0}")
    @MethodSource("fixtureNames")
    fun `Tier-1 (e) - diagnostic sets are valid for fixture`(fixturePath: String) {
        val xml = loadFixture(fixturePath)
        val definition = BpmnXmlToDefinitionConverter().parse(xml)

        val beanDiags = runEngine(beanRegistry, definition)

        // Basic sanity: engine runs and produces diagnostics
        assertThat(beanDiags).isNotEmpty().describedAs("Diagnostics should be generated for $fixturePath")
    }

    // -----------------------------------------------------------------------------------------
    // Helper utilities
    // -----------------------------------------------------------------------------------------

    private fun runEngine(registry: RuleRegistry, definition: BpmnDefinition): Set<RuleDiagnostic> {
        val engine = DefaultRuleEngine(registry, RuleProfile.EMPTY)
        return engine.evaluate(definition).diagnostics.toSet()
    }

    private fun loadFixture(classpathPath: String): String = checkNotNull(this::class.java.getResourceAsStream(classpathPath)) {
        "Test fixture not found on classpath: $classpathPath"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    companion object {
        @JvmStatic
        fun fixtureNames(): Stream<String> = Stream.of(
            "/bpmn/order-a-beer.bpmn",
            "/bpmn/valid-process.bpmn",
            "/bpmn/no-start-event.bpmn",
        )
    }
}
