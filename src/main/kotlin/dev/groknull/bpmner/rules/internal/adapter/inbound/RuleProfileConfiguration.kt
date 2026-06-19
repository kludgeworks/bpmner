/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.adapter.inbound

import dev.groknull.bpmner.rules.BpmnerLintConfig
import dev.groknull.bpmner.rules.RuleProfile
import dev.groknull.bpmner.rules.internal.domain.RuleProfileFactory
import dev.groknull.bpmner.rules.internal.domain.beans.BeanRuleRegistry
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
