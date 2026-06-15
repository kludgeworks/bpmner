/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.rules.internal.domain.compiled.DanglingEdgeRule
import dev.groknull.bpmner.rules.internal.domain.compiled.DefaultFlowRule
import dev.groknull.bpmner.rules.internal.domain.compiled.DuplicateIdRule
import dev.groknull.bpmner.rules.internal.domain.compiled.EventDefinitionRule
import dev.groknull.bpmner.rules.internal.domain.compiled.RequiredEventsRule
import dev.groknull.bpmner.rules.internal.domain.compiled.RequiredNameRule
import dev.groknull.bpmner.rules.internal.domain.compiled.TaskPayloadRule
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlpConfig
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource

/**
 * Builds and refreshes an isolated [AnnotationConfigApplicationContext] wired for the
 * Kotlin bean rule source (`bpmner.rules.source=kotlin`): the 12 category `@Configuration`
 * classes, [BeanRuleRegistry], the 7 compiled `@Component` rules, and [BpmnNlpConfig].
 *
 * Shared by [BeanRuleRegistryConstructionTest] and [RuleSourceParityTest] so the bean
 * registration list lives in exactly one place — duplicating it across both tests trips
 * the CPD copy/paste gate. The caller owns the returned context's lifecycle (close it via
 * `use {}` or an `@AfterAll` teardown).
 */
internal fun bpmnerKotlinRuleContext(): AnnotationConfigApplicationContext = AnnotationConfigApplicationContext().apply {
    environment.propertySources.addFirst(
        MapPropertySource("test", mapOf("bpmner.rules.source" to "kotlin")),
    )
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
