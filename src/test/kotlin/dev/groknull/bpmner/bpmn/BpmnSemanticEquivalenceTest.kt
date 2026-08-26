/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the layout-seam equivalence check (issue #746).
 *
 * Both directions matter. If it never fires, the pair can still diverge silently; if it fires on
 * cosmetic differences it would fail generation outright, because the caller turns a divergence
 * into a `LayoutFailed` terminal.
 */
class BpmnSemanticEquivalenceTest {
    private fun definition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Reference process",
        nodes = listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnUserTask("Task_1", "Do work"),
            BpmnEndEvent("End_1", "Done"),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "Start_1", "Task_1"),
            BpmnEdge("Flow_2", "Task_1", "End_1"),
        ),
        participants = listOf(BpmnParticipant("Participant_1", "Supplier")),
        lanes = listOf(BpmnLane("Lane_1", "Warehouse", "Participant_1", listOf("Task_1"))),
        messageFlows = listOf(BpmnMessageFlow("MessageFlow_1", "Task_1", "Participant_1", "Order")),
    )

    @Test
    fun `identical definitions are equivalent`() {
        assertEquals(emptyList(), definition().semanticDivergenceFrom(definition()))
    }

    @Test
    fun `diagram-interchange bookkeeping is not a divergence`() {
        // Layout's whole job is to add DI. That must never read as "the process changed".
        val laidOut = definition().copy(diagramCount = 1)
        assertEquals(emptyList(), definition().semanticDivergenceFrom(laidOut))
    }

    @Test
    fun `document ordering is not a divergence`() {
        // The renderer and the parser need not agree on element order.
        val reordered = definition().let { d ->
            d.copy(nodes = d.nodes.reversed(), sequences = d.sequences.reversed())
        }
        assertEquals(emptyList(), definition().semanticDivergenceFrom(reordered))
    }

    @Test
    fun `a dropped node is caught`() {
        val damaged = definition().let { d -> d.copy(nodes = d.nodes.filterNot { it.id == "Task_1" }) }
        val divergences = definition().semanticDivergenceFrom(damaged)

        assertTrue(divergences.any { it.contains("nodes lost") && it.contains("Task_1") }, "got: $divergences")
    }

    @Test
    fun `a rewired sequence flow is caught even though its id survives`() {
        val damaged = definition().let { d ->
            d.copy(sequences = d.sequences.map { if (it.id == "Flow_2") it.copy(targetRef = "Start_1") else it })
        }
        val divergences = definition().semanticDivergenceFrom(damaged)

        assertTrue(divergences.any { it.contains("sequence flows") }, "rewiring must be caught, got: $divergences")
    }

    @Test
    fun `dropped collaboration content is caught`() {
        val damaged = definition().copy(participants = emptyList(), lanes = emptyList(), messageFlows = emptyList())
        val divergences = definition().semanticDivergenceFrom(damaged)

        assertTrue(divergences.any { it.contains("participants lost") }, "got: $divergences")
        assertTrue(divergences.any { it.contains("lanes lost") }, "got: $divergences")
        assertTrue(divergences.any { it.contains("message flows lost") }, "got: $divergences")
    }

    @Test
    fun `a renamed process is caught`() {
        val damaged = definition().copy(processName = "Something else")
        assertTrue(definition().semanticDivergenceFrom(damaged).any { it.contains("processName changed") })
    }
}
