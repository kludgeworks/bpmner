/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.beans

import dev.groknull.bpmner.ruleset.BpmnerLintConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * Proves `name-uncommon-abbreviations` reads its repair replacement map from
 * [BpmnerLintConfig.abbreviationReplacements] rather than a hardcoded literal — the
 * loader→rule wiring `ConventionsLoaderTest` and `RuleDocsGoldenTest` can't distinguish
 * on their own, since both pass unchanged whether the value is config-driven or
 * hardcoded to the same defaults (Stage 1 exit gate, `plans/714/ARCHITECTURE.md:232`).
 */
internal class NameRuleConfigTest {
    @Test
    fun `name-uncommon-abbreviations replacement map comes from configured lint config`() {
        val configured = mapOf("ZXY" to "zenith crossing yard")
        val context: AnnotationConfigApplicationContext =
            bpmnerKotlinRuleContext(lintConfig = BpmnerLintConfig(abbreviationReplacements = configured))

        val rule = context.use {
            it.getBean(BeanRuleRegistry::class.java).ruleByIdOrAlias("name-uncommon-abbreviations")
        }

        assertThat(rule).describedAs("name-uncommon-abbreviations rule").isNotNull
        assertThat(rule?.metadata?.repair?.replacementMap).isEqualTo(configured)
    }
}
