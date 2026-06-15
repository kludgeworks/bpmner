/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("MaxLineLength")

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.api.BpmnDefinition
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.generation.BpmnXmlToDefinitionConverter
import dev.groknull.bpmner.rules.RuleProfile
import dev.groknull.bpmner.rules.RuleRegistry
import dev.groknull.bpmner.rules.internal.domain.CompositeRule
import dev.groknull.bpmner.rules.internal.domain.DefaultRuleEngine
import dev.groknull.bpmner.rules.internal.domain.DeterministicRule
import dev.groknull.bpmner.rules.internal.domain.PklRuleCatalog
import dev.groknull.bpmner.rules.internal.domain.compiled.DanglingEdgeRule
import dev.groknull.bpmner.rules.internal.domain.compiled.DefaultFlowRule
import dev.groknull.bpmner.rules.internal.domain.compiled.DuplicateIdRule
import dev.groknull.bpmner.rules.internal.domain.compiled.EventDefinitionRule
import dev.groknull.bpmner.rules.internal.domain.compiled.RequiredEventsRule
import dev.groknull.bpmner.rules.internal.domain.compiled.RequiredNameRule
import dev.groknull.bpmner.rules.internal.domain.compiled.TaskPayloadRule
import dev.groknull.bpmner.rules.internal.domain.nlp.testBpmnNlp
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
 * Parity gate: proves the shadow Kotlin bean catalog ([BeanRuleRegistry]) is behaviourally
 * equivalent to the live Pkl catalog ([PklRuleCatalog]) before the #380 cutover.
 *
 * Implements the two-tier parity model from ADR-376-004:
 *
 * - **Tier-1 (blocking):** id set, [RuleCategory], [RuleSeverity], `targetElements`,
 *   `checkPrimitive`, and the typed [DeterministicCheckConfig]/[CompositeCheckConfig].
 * - **Tier-2 (advisory, logged only):** `repair` defaults and `staticConfig` legitimately
 *   differ in faithful ports (see architecture.md risk #8 and REVIEW-378.md row 12).
 *
 * Fixture diagnostics are compared as **sets** (order-insensitive) using [RuleDiagnostic],
 * which is a data class — structural equality is well-defined.
 *
 * Count assertion: derived from [PklRuleCatalog.activeRules] (expected 47 = 40 + 7 compiled),
 * NOT the stale issue prose value of 54 (ADR-376-003, architecture.md:128, 175).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("TooManyFunctions")
internal class RuleSourceParityTest {
    private lateinit var pklRegistry: RuleRegistry
    private lateinit var beanRegistry: RuleRegistry
    private lateinit var beanContext: AnnotationConfigApplicationContext

    @BeforeAll
    fun setUp() {
        val nlp = testBpmnNlp()
        val compiledRules = listOf(
            DanglingEdgeRule(),
            DefaultFlowRule(),
            DuplicateIdRule(),
            EventDefinitionRule(),
            RequiredEventsRule(),
            RequiredNameRule(),
            TaskPayloadRule(),
        )

        // Build the Pkl catalog directly — same idiom as PklRuleCatalogTest.
        pklRegistry = PklRuleCatalog(compiledRules, nlp)

        // Build the bean registry via an isolated Spring context with bpmner.rules.source=kotlin
        // (shared helper; see BeanRuleRegistryConstructionTest). The context is held open for the
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
    // Tier-1 (a): Id-set equality
    // -----------------------------------------------------------------------------------------

    @Test
    fun `Tier-1 (a) - both catalogs expose identical active rule id sets`() {
        val pklIds = pklRegistry.activeRules().map { it.id }.toSet()
        val beanIds = beanRegistry.activeRules().map { it.id }.toSet()

        // Guard that the Pkl catalog actually loaded rules — the id-set comparison below is only
        // meaningful when both catalogs are non-empty. The live total is 47 (40 Pkl-derived + 7
        // compiled), but the gate compares the two catalogs' live id sets rather than hardcoding a
        // count (NOT the stale issue prose "54"; see ADR-376-003).
        assertThat(pklIds).describedAs("Pkl catalog rule ids").isNotEmpty()
        assertThat(beanIds)
            .describedAs(
                "Bean catalog must expose exactly the same ids as Pkl catalog.\n" +
                    "Missing from bean: %s\nExtra in bean: %s",
                pklIds - beanIds,
                beanIds - pklIds,
            )
            .containsExactlyInAnyOrderElementsOf(pklIds)
    }

    @Test
    fun `Tier-1 (a) - both catalogs have the same active rule count`() {
        val pklCount = pklRegistry.activeRules().size
        val beanCount = beanRegistry.activeRules().size
        assertThat(beanCount)
            .describedAs("Bean registry must have the same active-rule count as Pkl registry (expected $pklCount)")
            .isEqualTo(pklCount)
    }

    // -----------------------------------------------------------------------------------------
    // Tier-1 (b): Per-id metadata projection (category, severity, targetElements, checkPrimitive)
    // -----------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod")
    fun `Tier-1 (b) - per-id Tier-1 metadata matches between catalogs`() {
        val pklById = pklRegistry.activeRules().associateBy { it.id }
        val beanById = beanRegistry.activeRules().associateBy { it.id }
        val sharedIds = pklById.keys.intersect(beanById.keys)

        val mismatches = mutableListOf<String>()
        for (id in sharedIds.sorted()) {
            val pklMeta = pklById.getValue(id).metadata
            val beanMeta = beanById.getValue(id).metadata

            val errors = mutableListOf<String>()

            if (pklMeta.category != beanMeta.category) {
                errors += "category: pkl=${pklMeta.category.displayName} bean=${beanMeta.category.displayName}"
            }
            if (pklMeta.severity != beanMeta.severity) {
                errors += "severity: pkl=${pklMeta.severity} bean=${beanMeta.severity}"
            }
            if (pklMeta.targetElements.toSet() != beanMeta.targetElements.toSet()) {
                errors += "targetElements: pkl=${pklMeta.targetElements} bean=${beanMeta.targetElements}"
            }
            if (pklMeta.checkPrimitive != beanMeta.checkPrimitive) {
                errors += "checkPrimitive: pkl=${pklMeta.checkPrimitive} bean=${beanMeta.checkPrimitive}"
            }

            if (errors.isNotEmpty()) {
                mismatches += "[$id]: ${errors.joinToString("; ")}"
            }
        }

        // Log Tier-2 advisory diffs (repair/staticConfig) — do NOT block on them.
        logTier2Diffs(pklById, beanById, sharedIds)

        assertThat(mismatches)
            .describedAs("Tier-1 metadata parity failures (category, severity, targetElements, checkPrimitive)")
            .isEmpty()
    }

    // -----------------------------------------------------------------------------------------
    // Tier-1 (c): Per-id typed config equality
    // -----------------------------------------------------------------------------------------

    @Test
    fun `Tier-1 (c) - per-id typed check config matches between catalogs`() {
        val pklById = pklRegistry.activeRules().associateBy { it.id }
        val beanById = beanRegistry.activeRules().associateBy { it.id }
        val sharedIds = pklById.keys.intersect(beanById.keys)

        val mismatches = mutableListOf<String>()
        for (id in sharedIds.sorted()) {
            val pklRule = pklById.getValue(id)
            val beanRule = beanById.getValue(id)

            val typedConfigError = when {
                pklRule is DeterministicRule && beanRule is DeterministicRule -> {
                    if (pklRule.config != beanRule.config) {
                        "DeterministicCheckConfig mismatch: pkl=${pklRule.config::class.simpleName} bean=${beanRule.config::class.simpleName}"
                    } else {
                        null
                    }
                }
                pklRule is CompositeRule && beanRule is CompositeRule -> {
                    if (pklRule.config != beanRule.config) {
                        "CompositeCheckConfig mismatch:\n  pkl=${pklRule.config}\n  bean=${beanRule.config}"
                    } else {
                        null
                    }
                }
                pklRule is DeterministicRule && beanRule is CompositeRule ->
                    "type mismatch: pkl=DeterministicRule, bean=CompositeRule"
                pklRule is CompositeRule && beanRule is DeterministicRule ->
                    "type mismatch: pkl=CompositeRule, bean=DeterministicRule"
                else ->
                    // Both are compiled @Component rules (DanglingEdge, etc.) — no config to compare.
                    null
            }

            if (typedConfigError != null) {
                mismatches += "[$id]: $typedConfigError"
            }
        }

        assertThat(mismatches)
            .describedAs("Tier-1 typed config parity failures (DeterministicCheckConfig/CompositeCheckConfig)")
            .isEmpty()
    }

    // -----------------------------------------------------------------------------------------
    // Tier-1 (d): Fixture-diagnostic parity (set-compared)
    // -----------------------------------------------------------------------------------------

    @ParameterizedTest(name = "fixture diagnostics match for {0}")
    @MethodSource("fixtureNames")
    fun `Tier-1 (d) - diagnostic sets match between catalogs for fixture`(fixturePath: String) {
        val xml = loadFixture(fixturePath)
        val definition = BpmnXmlToDefinitionConverter().parse(xml)

        val pklDiags = runEngine(pklRegistry, definition)
        val beanDiags = runEngine(beanRegistry, definition)

        assertThat(beanDiags)
            .describedAs(
                "Bean-path diagnostics must match Pkl-path diagnostics (set-compared) for $fixturePath.\n" +
                    "Only in Pkl: %s\nOnly in Bean: %s",
                pklDiags - beanDiags,
                beanDiags - pklDiags,
            )
            .containsExactlyInAnyOrderElementsOf(pklDiags)
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

    /**
     * Logs Tier-2 advisory diffs (repair defaults and staticConfig). These legitimately
     * differ in faithful ports and are NOT blocking assertions — see ADR-376-004 and
     * REVIEW-378.md row 12 for the known `name-business-meaningful-label` divergence.
     */
    private fun logTier2Diffs(
        pklById: Map<String, BpmnRule>,
        beanById: Map<String, BpmnRule>,
        sharedIds: Set<String>,
    ) {
        val tier2Diffs = mutableListOf<String>()
        for (id in sharedIds.sorted()) {
            val pklMeta = pklById.getValue(id).metadata
            val beanMeta = beanById.getValue(id).metadata
            val diffs = mutableListOf<String>()
            if (pklMeta.repair != beanMeta.repair) {
                diffs += "repair: pkl=${pklMeta.repair} bean=${beanMeta.repair}"
            }
            if (pklMeta.staticConfig != beanMeta.staticConfig) {
                diffs += "staticConfig differs"
            }
            if (diffs.isNotEmpty()) {
                tier2Diffs += "[$id] ${diffs.joinToString("; ")}"
            }
        }
        if (tier2Diffs.isNotEmpty()) {
            println(
                "\n[RuleSourceParityTest] Tier-2 advisory diffs (repair/staticConfig — NOT blocking):\n" +
                    tier2Diffs.joinToString("\n"),
            )
        }
    }

    companion object {
        @JvmStatic
        fun fixtureNames(): Stream<String> = Stream.of(
            "/bpmn/order-a-beer.bpmn",
            "/bpmn/valid-process.bpmn",
            "/bpmn/no-start-event.bpmn",
        )
    }
}
