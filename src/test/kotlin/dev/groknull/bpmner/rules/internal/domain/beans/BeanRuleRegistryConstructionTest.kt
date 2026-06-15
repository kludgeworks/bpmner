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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource

internal class BeanRuleRegistryConstructionTest {
    @Test
    fun `kotlin bean registry loads active rules with unique ids`() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(
                MapPropertySource("test", mapOf("bpmner.rules.source" to "kotlin")),
            )
            context.register(
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

            context.refresh()

            val rules = context.getBean(BeanRuleRegistry::class.java).activeRules()
            val ids = rules.map { it.id }

            assertThat(rules).hasSize(47)
            assertThat(ids).doesNotHaveDuplicates()
            assertThat(ids).contains("msg-message-flow-name-pattern")
            assertThat(ids).doesNotContain(
                "art-information-item-vs-application-component",
                "data-envelope-icon-usage",
                "def-business-process-element-usage",
                "evt-receive-task-vs-intermediate-message-event",
                "evt-send-task-vs-throwing-message-event",
                "evt-signal-events-broadcast-only-when-needed",
                "act-task-vs-subprocess-vs-call-activity",
                "gen-business-clarity-over-technical-detail",
                "gtw-exclusive-inclusive-parallel-semantics",
            )
        }
    }
}
