/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.adapter.inbound

import dev.groknull.bpmner.ruleset.BpmnerLintConfig
import dev.groknull.bpmner.ruleset.RuleProfile
import dev.groknull.bpmner.ruleset.internal.domain.RuleProfileFactory
import dev.groknull.bpmner.ruleset.internal.domain.beans.BeanRuleRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class RuleProfileConfiguration(
    private val beanRegistryProvider: ObjectProvider<BeanRuleRegistry>,
) {
    @Bean
    fun ruleProfile(lintConfig: BpmnerLintConfig): RuleProfile = RuleProfileFactory(beanRegistryProvider).ruleProfile(lintConfig)
}
