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
    @Suppress("LongMethod")
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

            // All 40 active Pkl-derived bean ids (excluding 9 deferred rules).
            // Includes the 7 compiled Kotlin rules in the total count.
            assertThat(ids).contains(
                // Activity (5)
                "act-activity-label-capitalization",
                "act-discouraged-business-verbs",
                "act-loop-task-annotation",
                "act-mi-task-annotation",
                "act-verb-object-name",
                // Artifact (2)
                "art-group-usage",
                "art-text-annotation-usage",
                // Association (1)
                "assoc-required-annotation-association",
                // Data (1)
                "data-no-type-words-in-data-name",
                // Event (10)
                "evt-boundary-event-constraints",
                "evt-error-end-boundary-pair",
                "evt-event-state-name",
                "evt-event-state-pattern",
                "evt-intermediate-event-not-action",
                "evt-link-event-pairing",
                "evt-message-start-has-message-flow",
                "evt-start-no-incoming",
                "evt-timer-start-events-block-until-time",
                // Flow (2)
                "flow-diverging-flow-outcome-label",
                "flow-sequence-flow-within-pool",
                // Gateway (9)
                "gtw-converging-gateway-unnamed",
                "gtw-diverging-flow-names",
                "gtw-diverging-gateway-question",
                "gtw-event-based-direct-events",
                "gtw-fake-join",
                "gtw-gateway-no-work-label",
                "gtw-no-gateway-join-fork",
                "gtw-superfluous-gateway",
                // General (2)
                "gen-bpmn-subset",
                "gen-no-duplicate-diagrams",
                // Lane (2)
                "lane-actor-artifact-usage",
                "lane-lane-labels-business-roles-performers",
                // Message (2)
                "msg-message-flow-across-pools",
                "msg-message-flow-name-pattern",
                // Name (3)
                "name-business-meaningful-label",
                "name-no-element-type-words",
                "name-uncommon-abbreviations",
                // Pool (3)
                "pool-black-box-pool-named-by-external-entity-or-process",
                "pool-child-diagrams-keep-pool-process-name",
                "pool-white-box-pool-named-by-process",
            )

            // The 9 deferred/no-primitive rules must be absent.
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
