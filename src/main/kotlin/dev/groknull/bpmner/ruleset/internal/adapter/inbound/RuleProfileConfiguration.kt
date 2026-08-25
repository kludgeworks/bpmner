/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.adapter.inbound

import dev.groknull.bpmner.pkl.BpmnerLintConfig
import dev.groknull.bpmner.ruleset.RuleProfile
import dev.groknull.bpmner.ruleset.internal.domain.RuleProfileFactory
import dev.groknull.bpmner.ruleset.internal.domain.beans.BeanRuleRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(BpmnerLintConfig::class)
internal class RuleProfileConfiguration(
    private val beanRegistryProvider: ObjectProvider<BeanRuleRegistry>,
) {
    @Bean
    fun ruleProfile(lintConfig: BpmnerLintConfig): RuleProfile = RuleProfileFactory(beanRegistryProvider).ruleProfile(lintConfig)
}
