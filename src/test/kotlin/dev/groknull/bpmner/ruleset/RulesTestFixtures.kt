/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset

import dev.groknull.bpmner.ruleset.internal.domain.DefaultRuleEngine
import dev.groknull.bpmner.ruleset.internal.domain.InMemoryRuleRegistry
import dev.groknull.bpmner.ruleset.internal.domain.beans.bpmnerKotlinRuleContext
import dev.groknull.bpmner.ruleset.internal.domain.compiled.DanglingEdgeRule
import dev.groknull.bpmner.ruleset.internal.domain.compiled.DefaultFlowRule
import dev.groknull.bpmner.ruleset.internal.domain.compiled.DuplicateIdRule
import dev.groknull.bpmner.ruleset.internal.domain.compiled.EventDefinitionRule
import dev.groknull.bpmner.ruleset.internal.domain.compiled.RequiredEventsRule
import dev.groknull.bpmner.ruleset.internal.domain.compiled.RequiredNameRule
import dev.groknull.bpmner.ruleset.internal.domain.compiled.TaskPayloadRule
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * Published test fixture for the `rules` module.
 *
 * These helpers live in the `rules` module's test scope (same-module reach into
 * `rules.internal.*`). Cross-module callers import only this object from the `rules`
 * root, never reaching into internal packages directly.
 *
 * (S5 — ARCHITECTURE §5 S5, §1.5; cross-module test fixture published at module root)
 */
object RulesTestFixtures {
    /**
     * Creates a [RuleEngine] pre-loaded with the 7 standard compiled rules.
     * Returns the [RuleEngine] root-package interface so callers need not import
     * any internal types.
     */
    @JvmStatic
    fun standardCompiledRuleEngine(): RuleEngine {
        return DefaultRuleEngine(
            InMemoryRuleRegistry(
                listOf(
                    DuplicateIdRule(),
                    RequiredNameRule(),
                    DanglingEdgeRule(),
                    RequiredEventsRule(),
                    EventDefinitionRule(),
                    TaskPayloadRule(),
                    DefaultFlowRule(),
                ),
            ),
            RuleProfile.EMPTY,
        )
    }

    /**
     * Creates an empty [RuleRegistry] backed by no rules.
     * Returns the [RuleRegistry] root-package interface.
     */
    @JvmStatic
    fun emptyRegistry(): RuleRegistry = InMemoryRuleRegistry(emptyList())

    /**
     * Builds and returns a Spring [AnnotationConfigApplicationContext] wired with the
     * full Kotlin bean rule source (all category configs, [BeanRuleRegistry], the 7
     * compiled rules, and LLM rule specs). The caller owns the context lifecycle —
     * close it via `use {}` or an `@AfterAll` teardown.
     *
     * Published from the `rules` root so validation-module tests need not reach into
     * `rules.internal.domain.beans` (S5 — ARCHITECTURE §5 S5, §1.5).
     */
    @JvmStatic
    fun fullBeanRuleContext(
        lintConfig: BpmnerLintConfig = BpmnerLintConfig(),
    ): AnnotationConfigApplicationContext = bpmnerKotlinRuleContext(lintConfig)
}
