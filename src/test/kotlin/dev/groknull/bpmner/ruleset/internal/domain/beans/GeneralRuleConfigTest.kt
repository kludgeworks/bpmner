/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.beans

import dev.groknull.bpmner.ruleset.defaultBpmnerLintConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class GeneralRuleConfigTest {
    @Test
    fun `gen-bpmn-subset targets default banned types`() {
        val context = bpmnerKotlinRuleContext(lintConfig = defaultBpmnerLintConfig())
        val rule = context.use {
            it.getBean(BeanRuleRegistry::class.java).ruleByIdOrAlias("gen-bpmn-subset")
        }

        assertThat(rule).describedAs("gen-bpmn-subset rule").isNotNull
        assertThat(rule?.metadata?.targetElements).contains("bpmn:UserTask", "bpmn:ComplexGateway", "bpmn:DataObject")
    }

    @Test
    fun `gen-bpmn-subset targets overridden banned types`() {
        val context = bpmnerKotlinRuleContext(lintConfig = defaultBpmnerLintConfig().withBannedBpmnTypes(listOf("bpmn:UserTask")))
        val rule = context.use {
            it.getBean(BeanRuleRegistry::class.java).ruleByIdOrAlias("gen-bpmn-subset")
        }

        assertThat(rule).describedAs("gen-bpmn-subset rule").isNotNull
        assertThat(rule?.metadata?.targetElements).containsExactly("bpmn:UserTask")
    }
}
