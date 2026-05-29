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
 * #282 reframe: previously the parser hard-errored on these types
 * (`BpmnXmlToDefinitionConverter.toBpmnNode()` had `else -> error(...)`). They now flow
 * through the model as `BpmnUnrecognizedNode` / `BpmnUnrecognizedEventDefinition`, and this
 * rule does the policy enforcement. The three test branches exercise the three pathways the
 * parser surfaces exotic types via:
 *
 *  - **FlowNode else-branch**: `bpmn:Transaction` (a SubProcess subtype that misses the
 *    `when (this)` arms in `toBpmnNode`).
 *  - **DOM scan for top-level constructs**: `bpmn:Choreography` (not a FlowNode; surfaced
 *    via the namespaced DOM lookup in `exoticTopLevelNodes`).
 *  - **DOM scan for event definitions**: `bpmn:CompensateEventDefinition` (attached to an
 *    event, surfaced via `Element.eventDefinition()` and emitted as a separate
 *    `PrimitiveElement` keyed off the parent event's id).
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
