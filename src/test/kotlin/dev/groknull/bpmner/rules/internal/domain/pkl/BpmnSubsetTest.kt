/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.pkl

import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.core.BpmnUnrecognizedNode
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.assertFires
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.assertSilent
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.context
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.loadRule
import org.junit.jupiter.api.Test

/**
 * Per-rule test for `gen-bpmn-subset`. The rule fires on any element whose typename matches
 * one of the 10 discouraged types in `targetElements` (Choreography, Conversation, Transaction
 * variants, and exotic event-definition typenames).
 *
 * The three fires-cases below exercise the three pathways the parser uses to surface exotic
 * types into `BpmnDefinition.nodes` and the primitive model:
 *
 *  - **FlowNode `else` arm**: `bpmn:Transaction` is a SubProcess subtype that
 *    `BpmnXmlToDefinitionConverter.toBpmnNode()` doesn't translate into a typed class; it
 *    becomes a `BpmnUnrecognizedNode`.
 *  - **DOM scan**: `bpmn:Choreography` isn't a FlowNode and is picked up by the namespaced
 *    DOM scan in the converter; it also becomes a `BpmnUnrecognizedNode`.
 *  - **Event-definition emission**: `bpmn:CompensateEventDefinition` is attached to an event
 *    as a `BpmnUnrecognizedEventDefinition`; `PrimitiveModelMapping` emits a separate
 *    `PrimitiveElement` keyed `<eventId>.eventDefinition` so the rule can target it.
 */
internal class BpmnSubsetTest {
    private val rule = loadRule("gen-bpmn-subset")

    @Test
    fun `silent on a definition with only supported types`() {
        assertSilent(
            rule,
            context(
                nodes = listOf<BpmnNode>(
                    BpmnStartEvent("s", "Start"),
                    BpmnUserTask("t", "Process invoice"),
                    BpmnEndEvent("e", "End"),
                ),
            ),
        )
    }

    @Test
    fun `fires on bpmn Transaction (FlowNode else-branch pathway)`() {
        assertFires(
            rule,
            context(
                nodes = listOf<BpmnNode>(
                    BpmnStartEvent("s", "Start"),
                    BpmnUnrecognizedNode(id = "tx1", bpmnType = "bpmn:Transaction"),
                    BpmnEndEvent("e", "End"),
                ),
            ),
            expectedElementIds = listOf("tx1"),
        )
    }

    @Test
    fun `fires on bpmn Choreography (DOM-scan pathway)`() {
        assertFires(
            rule,
            context(
                nodes = listOf<BpmnNode>(
                    BpmnStartEvent("s", "Start"),
                    BpmnUnrecognizedNode(id = "ch1", bpmnType = "bpmn:Choreography"),
                    BpmnEndEvent("e", "End"),
                ),
            ),
            expectedElementIds = listOf("ch1"),
        )
    }

    @Test
    fun `fires on bpmn CompensateEventDefinition (event-def pathway)`() {
        // The parent event itself is a supported BpmnStartEvent; the diagnostic anchors at
        // the event-definition's synthesized PrimitiveElement id (`<eventId>.eventDefinition`).
        assertFires(
            rule,
            context(
                nodes = listOf<BpmnNode>(
                    BpmnStartEvent(
                        id = "se",
                        name = "Start",
                        eventDefinition = BpmnUnrecognizedEventDefinition("bpmn:CompensateEventDefinition"),
                    ),
                    BpmnEndEvent("e", "End"),
                ),
            ),
            expectedElementIds = listOf("se.eventDefinition"),
        )
    }
}
