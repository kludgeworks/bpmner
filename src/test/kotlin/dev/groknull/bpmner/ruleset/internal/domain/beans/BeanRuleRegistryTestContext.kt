/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.beans

import dev.groknull.bpmner.ruleset.BpmnerLintConfig
import dev.groknull.bpmner.ruleset.internal.domain.compiled.DanglingEdgeRule
import dev.groknull.bpmner.ruleset.internal.domain.compiled.DefaultFlowRule
import dev.groknull.bpmner.ruleset.internal.domain.compiled.DuplicateIdRule
import dev.groknull.bpmner.ruleset.internal.domain.compiled.EventDefinitionRule
import dev.groknull.bpmner.ruleset.internal.domain.compiled.RequiredEventsRule
import dev.groknull.bpmner.ruleset.internal.domain.compiled.RequiredNameRule
import dev.groknull.bpmner.ruleset.internal.domain.compiled.TaskPayloadRule
import dev.groknull.bpmner.ruleset.internal.domain.nlp.BpmnNlpConfig
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * Builds and refreshes an isolated [AnnotationConfigApplicationContext] wired for the
 * Kotlin bean rule source: the 12 category `@Configuration`
 * classes, [BeanRuleRegistry], the 7 compiled `@Component` rules, the 2 LLM rule spec beans,
 * and [BpmnNlpConfig].
 *
 * Shared by [BeanRuleRegistryConstructionTest] and [RuleSourceParityTest] so the bean
 * registration list lives in exactly one place — duplicating it across both tests trips
 * the CPD copy/paste gate. The caller owns the returned context's lifecycle (close it via
 * `use {}` or an `@AfterAll` teardown).
 */
internal fun bpmnerKotlinRuleContext(
    lintConfig: BpmnerLintConfig = BpmnerLintConfig(),
): AnnotationConfigApplicationContext = AnnotationConfigApplicationContext().apply {
    beanFactory.registerSingleton("bpmnerLintConfig", lintConfig)
    register(
        BpmnNlpConfig::class.java,
        ActivityRuleConfig::class.java,
        ArtifactRuleConfig::class.java,
        AssociationRuleConfig::class.java,
        DataRuleConfig::class.java,
        EventRuleConfig::class.java,
        FlowRuleConfig::class.java,
        GatewayRuleConfig::class.java,
        GeneralRuleConfig::class.java,
        LaneRuleConfig::class.java,
        MessageRuleConfig::class.java,
        NameRuleConfig::class.java,
        PoolRuleConfig::class.java,
        BeanRuleRegistry::class.java,
        DanglingEdgeRule::class.java,
        DefaultFlowRule::class.java,
        DuplicateIdRule::class.java,
        EventDefinitionRule::class.java,
        RequiredEventsRule::class.java,
        RequiredNameRule::class.java,
        TaskPayloadRule::class.java,
    )
    refresh()
}
