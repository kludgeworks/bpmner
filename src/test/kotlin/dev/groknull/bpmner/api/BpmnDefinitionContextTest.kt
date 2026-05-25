/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnErrorRef
import dev.groknull.bpmner.core.BpmnEscalationRef
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnMessageRef
import dev.groknull.bpmner.core.BpmnSignalRef
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Asserts that [BpmnDefinitionContext] pre-computes every index the existing
 * `validation.BpmnDefinitionValidator` reaches for. A new compiled rule (#213) can rely on
 * this contract and skip its own scan of `definition.nodes` / `definition.sequences`.
 */
class BpmnDefinitionContextTest {
    /**
     * Curated fixture: start → review → gateway → (approve | reject) → end, with the
     * approve branch marked as the default flow on the exclusive gateway. Catalogs are
     * populated so the message/signal/error/escalation indexes have something to find.
     */
    private fun fixture(): BpmnDefinition = BpmnDefinition(
        processId = "Process_test",
        processName = "Order review",
        nodes =
        listOf(
            BpmnStartEvent(id = "start"),
            BpmnUserTask(id = "review", name = "Review order"),
            BpmnExclusiveGateway(id = "gw", name = "Approved?"),
            BpmnUserTask(id = "approve", name = "Approve order"),
            BpmnUserTask(id = "reject", name = "Reject order"),
            BpmnEndEvent(id = "end"),
        ),
        sequences =
        listOf(
            BpmnEdge(id = "f_start_review", sourceRef = "start", targetRef = "review"),
            BpmnEdge(id = "f_review_gw", sourceRef = "review", targetRef = "gw"),
            BpmnEdge(id = "f_approve", sourceRef = "gw", targetRef = "approve", isDefault = true),
            BpmnEdge(
                id = "f_reject",
                sourceRef = "gw",
                targetRef = "reject",
                conditionExpression = "\${rejected}",
            ),
            BpmnEdge(id = "f_approve_end", sourceRef = "approve", targetRef = "end"),
            BpmnEdge(id = "f_reject_end", sourceRef = "reject", targetRef = "end"),
        ),
        messages = listOf(BpmnMessageRef(id = "m_order_received", name = "OrderReceived")),
        signals = listOf(BpmnSignalRef(id = "s_market_close", name = "MarketClose")),
        errors = listOf(BpmnErrorRef(id = "e_invalid_order", code = "INVALID")),
        escalations = listOf(BpmnEscalationRef(id = "esc_supervisor", code = "ESCALATE")),
    )

    @Test
    fun `nodeIds contains every node id`() {
        val ctx = BpmnDefinitionContext(fixture())
        assertEquals(setOf("start", "review", "gw", "approve", "reject", "end"), ctx.nodeIds)
    }

    @Test
    fun `nodeIds and nodesById use raw ids consistently — whitespace is preserved`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes = listOf(BpmnStartEvent(id = "  start  "), BpmnEndEvent(id = "end")),
                    sequences = listOf(BpmnEdge(id = "f1", sourceRef = "  start  ", targetRef = "end")),
                ),
            )
        // Raw ids land in both indexes; trimmed lookups deliberately miss to avoid silent
        // resolution drift between `id in ctx.nodeIds` and `ctx.nodesById[id]`.
        assertTrue("  start  " in ctx.nodeIds)
        assertTrue("end" in ctx.nodeIds)
        assertNotNull(ctx.nodesById["  start  "])
        assertNull(ctx.nodesById["start"])
        // Trimmed comparison is the future DuplicateIdRule's concern; the context stays raw.
    }

    @Test
    fun `sequenceIds contains every edge id`() {
        val ctx = BpmnDefinitionContext(fixture())
        assertEquals(
            setOf("f_start_review", "f_review_gw", "f_approve", "f_reject", "f_approve_end", "f_reject_end"),
            ctx.sequenceIds,
        )
    }

    @Test
    fun `nodesById resolves every node id to its instance`() {
        val ctx = BpmnDefinitionContext(fixture())
        assertEquals(6, ctx.nodesById.size)
        assertNotNull(ctx.nodesById["gw"])
        assertTrue(ctx.nodesById["gw"] is BpmnExclusiveGateway)
        assertNull(ctx.nodesById["missing"])
    }

    @Test
    fun `messageIds signalIds errorIds escalationIds contain catalog entries`() {
        val ctx = BpmnDefinitionContext(fixture())
        assertEquals(setOf("m_order_received"), ctx.messageIds)
        assertEquals(setOf("s_market_close"), ctx.signalIds)
        assertEquals(setOf("e_invalid_order"), ctx.errorIds)
        assertEquals(setOf("esc_supervisor"), ctx.escalationIds)
    }

    @Test
    fun `outgoingCounts reflects per-source edge count`() {
        val ctx = BpmnDefinitionContext(fixture())
        // gw fans out to approve + reject = 2
        assertEquals(2, ctx.outgoingCounts["gw"])
        // start, review, approve, reject each emit exactly 1
        assertEquals(1, ctx.outgoingCounts["start"])
        assertEquals(1, ctx.outgoingCounts["review"])
        assertEquals(1, ctx.outgoingCounts["approve"])
        assertEquals(1, ctx.outgoingCounts["reject"])
        // end has no outgoing
        assertNull(ctx.outgoingCounts["end"])
    }

    @Test
    fun `defaultsBySource collects only edges with isDefault true`() {
        val ctx = BpmnDefinitionContext(fixture())
        assertEquals(1, ctx.defaultsBySource["gw"]?.size)
        assertEquals("f_approve", ctx.defaultsBySource["gw"]?.single()?.id)
        // No other source has a default flow.
        assertNull(ctx.defaultsBySource["start"])
        assertNull(ctx.defaultsBySource["review"])
    }

    @Test
    fun `empty definition produces empty indexes`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes = listOf(BpmnStartEvent(id = "s"), BpmnEndEvent(id = "e")),
                    sequences = listOf(BpmnEdge(id = "f", sourceRef = "s", targetRef = "e")),
                ),
            )
        assertEquals(emptySet<String>(), ctx.messageIds)
        assertEquals(emptySet<String>(), ctx.signalIds)
        assertEquals(emptySet<String>(), ctx.errorIds)
        assertEquals(emptySet<String>(), ctx.escalationIds)
        assertEquals(emptyMap<String, List<BpmnEdge>>(), ctx.defaultsBySource)
    }
}
