/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

import dev.groknull.bpmner.bpmn.internal.model.BpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnEndEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.internal.model.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnManualTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnNode
import dev.groknull.bpmner.bpmn.internal.model.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnParallelGateway
import dev.groknull.bpmner.bpmn.internal.model.BpmnReceiveTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnScriptTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnSendTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnServiceTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnStartEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnUserTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Exercises the `BpmnNode.withName` method (declared on `bpmn.BpmnNode`, overridden in each
 * concrete `core` data class via `copy(name = name)`). Asserts that withName preserves the
 * concrete subtype and replaces only the `name` field.
 */
class WithNameRoundTripTest {
    private fun roundTrip(
        original: BpmnNode,
        newName: String,
    ) {
        val renamed = original.withName(newName)
        assertEquals(original::class, renamed::class, "withName must preserve the concrete subtype")
        assertEquals(newName, renamed.name)
        assertEquals(original.id, renamed.id)
    }

    @Test
    fun `withName preserves subtype and replaces name on every BpmnNode kind`() {
        roundTrip(BpmnStartEvent(id = "s"), "Order received")
        roundTrip(BpmnEndEvent(id = "e"), "Order shipped")
        roundTrip(BpmnUserTask(id = "ut"), "Review order")
        roundTrip(BpmnServiceTask(id = "st"), "Charge card")
        roundTrip(BpmnScriptTask(id = "sct"), "Compute total")
        roundTrip(BpmnBusinessRuleTask(id = "br", decisionRef = "d"), "Apply discount rules")
        roundTrip(BpmnSendTask(id = "send", messageRef = "m"), "Notify warehouse")
        roundTrip(BpmnReceiveTask(id = "recv", messageRef = "m"), "Wait for ack")
        roundTrip(BpmnManualTask(id = "mt"), "Sign packing slip")
        roundTrip(BpmnExclusiveGateway(id = "xg"), "Is in stock?")
        roundTrip(BpmnParallelGateway(id = "pg"), "Fork")
        roundTrip(BpmnIntermediateCatchEvent(id = "ic", eventDefinition = BpmnNoneEventDefinition), "Wait for signal")
        roundTrip(BpmnIntermediateThrowEvent(id = "it", eventDefinition = BpmnNoneEventDefinition), "Emit signal")
        roundTrip(
            BpmnBoundaryEvent(id = "be", attachedToRef = "ut", eventDefinition = BpmnNoneEventDefinition),
            "On timeout",
        )
    }

    @Test
    fun `withName accepts null to clear the name`() {
        val task: BpmnNode = BpmnUserTask(id = "ut", name = "Review")
        val cleared = task.withName(null)
        assertEquals(null, cleared.name)
        assertEquals("ut", cleared.id)
    }
}
