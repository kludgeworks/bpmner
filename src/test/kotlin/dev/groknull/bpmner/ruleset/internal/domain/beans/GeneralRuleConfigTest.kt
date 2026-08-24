/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.beans

import dev.groknull.bpmner.ruleset.BpmnerLintConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class GeneralRuleConfigTest {
    @Test
    fun `gen-bpmn-subset targets style-guide discouraged types under style-guide profile`() {
        val context = bpmnerKotlinRuleContext(lintConfig = BpmnerLintConfig(profile = "style-guide"))
        val rule = context.use {
            it.getBean(BeanRuleRegistry::class.java).ruleByIdOrAlias("gen-bpmn-subset")
        }

        assertThat(rule).describedAs("gen-bpmn-subset rule").isNotNull
        assertThat(rule?.metadata?.targetElements).contains("bpmn:UserTask", "bpmn:ComplexGateway", "bpmn:DataObject")
    }

    @Test
    fun `gen-bpmn-subset targets only baseline discouraged types under recommended profile`() {
        val context = bpmnerKotlinRuleContext(lintConfig = BpmnerLintConfig(profile = "recommended"))
        val rule = context.use {
            it.getBean(BeanRuleRegistry::class.java).ruleByIdOrAlias("gen-bpmn-subset")
        }

        assertThat(rule).describedAs("gen-bpmn-subset rule").isNotNull
        assertThat(rule?.metadata?.targetElements).contains("bpmn:DataObject")
        assertThat(rule?.metadata?.targetElements).doesNotContain("bpmn:UserTask", "bpmn:ComplexGateway")
    }
}
