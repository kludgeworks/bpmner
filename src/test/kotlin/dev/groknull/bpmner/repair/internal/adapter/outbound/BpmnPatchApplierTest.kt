/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.repair.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperation
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperationType
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairPatch
import dev.groknull.bpmner.repair.internal.domain.PatchApplicationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BpmnPatchApplierTest {
    private val applier = BpmnPatchApplier()

    private val baseDefinition =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Test Process",
            nodes =
            listOf(
                BpmnStartEvent("Start_1", "Start"),
                BpmnUserTask("Task_1", "Do work"),
                BpmnEndEvent("End_1", "End"),
            ),
            sequences =
            listOf(
                BpmnEdge("Flow_1", "Start_1", "Task_1"),
                BpmnEdge("Flow_2", "Task_1", "End_1"),
            ),
        )

    // -------------------------------------------------------------------------
    // SET_NODE_NAME
    // -------------------------------------------------------------------------

    @Test
    fun `SET_NODE_NAME updates node name`() {
        val patch =
            patch(
                BpmnPatchOperation(BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Task_1", name = "New name"),
            )
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(baseDefinition, patch))
        assertEquals(
            "New name",
            result.definition.nodes
                .first { it.id == "Task_1" }
                .name,
        )
    }

    @Test
    fun `SET_NODE_NAME with same name is no-op`() {
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Task_1", name = "Do work"))
        assertIs<PatchApplicationResult.NoOp>(applier.apply(baseDefinition, patch))
    }

    @Test
    fun `SET_NODE_NAME with blank name is invalid`() {
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Task_1", name = ""))
        val result = assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
        assertTrue(result.reason.contains("blank"))
    }

    @Test
    fun `SET_NODE_NAME clears converging gateway name`() {
        val definition = convergingGatewayDefinition(gatewayName = "Decision merged")
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Gateway_1", name = ""))

        val result = assertIs<PatchApplicationResult.Success>(applier.apply(definition, patch))

        assertNull(
            result.definition.nodes
                .first { it.id == "Gateway_1" }
                .name,
        )
    }

    @Test
    fun `SET_NODE_NAME with null name clears converging gateway name`() {
        val definition = convergingGatewayDefinition(gatewayName = "Decision merged")
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Gateway_1", name = null))

        val result = assertIs<PatchApplicationResult.Success>(applier.apply(definition, patch))

        assertNull(
            result.definition.nodes
                .first { it.id == "Gateway_1" }
                .name,
        )
    }

    @Test
    fun `SET_NODE_NAME does not clear diverging gateway name`() {
        val definition = divergingGatewayDefinition()
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Gateway_1", name = ""))

        val result = assertIs<PatchApplicationResult.Failure>(applier.apply(definition, patch))

        assertTrue(result.reason.contains("blank"))
    }

    @Test
    fun `SET_NODE_NAME with unknown nodeId is invalid`() {
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Task_X", name = "fix"))
        val result = assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
        assertTrue(result.reason.contains("Task_X"))
    }

    @Test
    fun `SET_NODE_NAME without nodeId is invalid`() {
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.SET_NODE_NAME, name = "fix"))
        assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
    }

    // -------------------------------------------------------------------------
    // SET_EDGE_LABEL
    // -------------------------------------------------------------------------

    @Test
    fun `SET_EDGE_LABEL updates edge label`() {
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.SET_EDGE_LABEL, edgeId = "Flow_1", label = "Yes"))
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(baseDefinition, patch))
        assertEquals(
            "Yes",
            result.definition.sequences
                .first { it.id == "Flow_1" }
                .name,
        )
    }

    @Test
    fun `SET_EDGE_LABEL clears label when null`() {
        val withLabel =
            baseDefinition.copy(
                sequences =
                baseDefinition.sequences.map {
                    if (it.id == "Flow_1") it.copy(name = "Old label") else it
                },
            )
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.SET_EDGE_LABEL, edgeId = "Flow_1", label = null))
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(withLabel, patch))
        assertEquals(
            null,
            result.definition.sequences
                .first { it.id == "Flow_1" }
                .name,
        )
    }

    @Test
    fun `SET_EDGE_LABEL with unknown edgeId is invalid`() {
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.SET_EDGE_LABEL, edgeId = "Flow_X", label = "fix"))
        val result = assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
        assertTrue(result.reason.contains("Flow_X"))
    }

    // -------------------------------------------------------------------------
    // ADD_NODE
    // -------------------------------------------------------------------------

    @Test
    fun `ADD_NODE appends a new node`() {
        val newNode = BpmnServiceTask("Task_2", "New task")
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.ADD_NODE, node = newNode))
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(baseDefinition, patch))
        assertTrue(result.definition.nodes.any { it.id == "Task_2" })
        assertEquals(4, result.definition.nodes.size)
    }

    @Test
    fun `ADD_NODE with duplicate id is invalid`() {
        val dup = BpmnServiceTask("Task_1", "Duplicate")
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.ADD_NODE, node = dup))
        val result = assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
        assertTrue(result.reason.contains("Task_1"))
    }

    @Test
    fun `ADD_NODE without node is invalid`() {
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.ADD_NODE))
        assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
    }

    // -------------------------------------------------------------------------
    // REMOVE_NODE
    // -------------------------------------------------------------------------

    @Test
    fun `REMOVE_NODE removes a node with no edges`() {
        val isolated =
            baseDefinition.copy(
                nodes = baseDefinition.nodes + BpmnServiceTask("Isolated_1", "Isolated"),
            )
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.REMOVE_NODE, nodeId = "Isolated_1"))
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(isolated, patch))
        assertTrue(result.definition.nodes.none { it.id == "Isolated_1" })
    }

    @Test
    fun `REMOVE_NODE fails when node still referenced by edges`() {
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.REMOVE_NODE, nodeId = "Task_1"))
        val result = assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
        assertTrue(result.reason.contains("Task_1"))
    }

    @Test
    fun `REMOVE_NODE with unknown id is invalid`() {
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.REMOVE_NODE, nodeId = "Task_X"))
        assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
    }

    // -------------------------------------------------------------------------
    // REPLACE_NODE
    // -------------------------------------------------------------------------

    @Test
    fun `REPLACE_NODE swaps in replacement`() {
        val replacement = BpmnServiceTask("Task_1", "Replaced task")
        val patch =
            patch(
                BpmnPatchOperation(BpmnPatchOperationType.REPLACE_NODE, nodeId = "Task_1", node = replacement),
            )
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(baseDefinition, patch))
        assertEquals(
            "Replaced task",
            result.definition.nodes
                .first { it.id == "Task_1" }
                .name,
        )
    }

    @Test
    fun `REPLACE_NODE with id mismatch is invalid`() {
        val replacement = BpmnServiceTask("Task_2", "Wrong id")
        val patch =
            patch(
                BpmnPatchOperation(BpmnPatchOperationType.REPLACE_NODE, nodeId = "Task_1", node = replacement),
            )
        val result = assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
        assertTrue(result.reason.contains("mismatch") || result.reason.contains("Task_2"))
    }

    @Test
    fun `REPLACE_NODE with identical node is no-op`() {
        val same = baseDefinition.nodes.first { it.id == "Task_1" }
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.REPLACE_NODE, nodeId = "Task_1", node = same))
        assertIs<PatchApplicationResult.NoOp>(applier.apply(baseDefinition, patch))
    }

    // -------------------------------------------------------------------------
    // ADD_EDGE
    // -------------------------------------------------------------------------

    @Test
    fun `ADD_EDGE appends new sequence flow`() {
        val newEdge = BpmnEdge("Flow_3", "Start_1", "End_1")
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.ADD_EDGE, edge = newEdge))
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(baseDefinition, patch))
        assertEquals(3, result.definition.sequences.size)
    }

    @Test
    fun `ADD_EDGE with duplicate id is invalid`() {
        val dup = BpmnEdge("Flow_1", "Start_1", "End_1")
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.ADD_EDGE, edge = dup))
        assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
    }

    @Test
    fun `ADD_EDGE with unknown sourceRef is invalid`() {
        val bad = BpmnEdge("Flow_3", "Unknown_1", "End_1")
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.ADD_EDGE, edge = bad))
        val result = assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
        assertTrue(result.reason.contains("sourceRef"))
    }

    @Test
    fun `ADD_EDGE with unknown targetRef is invalid`() {
        val bad = BpmnEdge("Flow_3", "Start_1", "Unknown_1")
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.ADD_EDGE, edge = bad))
        val result = assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
        assertTrue(result.reason.contains("targetRef"))
    }

    // -------------------------------------------------------------------------
    // REMOVE_EDGE
    // -------------------------------------------------------------------------

    @Test
    fun `REMOVE_EDGE removes the edge`() {
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.REMOVE_EDGE, edgeId = "Flow_1"))
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(baseDefinition, patch))
        assertTrue(result.definition.sequences.none { it.id == "Flow_1" })
    }

    @Test
    fun `REMOVE_EDGE with unknown id is invalid`() {
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.REMOVE_EDGE, edgeId = "Flow_X"))
        assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
    }

    // -------------------------------------------------------------------------
    // REPLACE_EDGE
    // -------------------------------------------------------------------------

    @Test
    fun `REPLACE_EDGE swaps in replacement`() {
        val replacement = BpmnEdge("Flow_1", "Start_1", "Task_1", name = "start flow")
        val patch =
            patch(
                BpmnPatchOperation(BpmnPatchOperationType.REPLACE_EDGE, edgeId = "Flow_1", edge = replacement),
            )
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(baseDefinition, patch))
        assertEquals(
            "start flow",
            result.definition.sequences
                .first { it.id == "Flow_1" }
                .name,
        )
    }

    @Test
    fun `REPLACE_EDGE with id mismatch is invalid`() {
        val replacement = BpmnEdge("Flow_2", "Start_1", "Task_1")
        val patch =
            patch(
                BpmnPatchOperation(BpmnPatchOperationType.REPLACE_EDGE, edgeId = "Flow_1", edge = replacement),
            )
        assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
    }

    @Test
    fun `REPLACE_EDGE with identical edge is no-op`() {
        val same = baseDefinition.sequences.first { it.id == "Flow_1" }
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.REPLACE_EDGE, edgeId = "Flow_1", edge = same))
        assertIs<PatchApplicationResult.NoOp>(applier.apply(baseDefinition, patch))
    }

    // -------------------------------------------------------------------------
    // Multi-operation patches
    // -------------------------------------------------------------------------

    @Test
    fun `multiple operations applied in order`() {
        val patch =
            BpmnRepairPatch(
                operations =
                listOf(
                    BpmnPatchOperation(BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Task_1", name = "First fix"),
                    BpmnPatchOperation(BpmnPatchOperationType.SET_EDGE_LABEL, edgeId = "Flow_1", label = "start"),
                ),
            )
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(baseDefinition, patch))
        assertEquals(
            "First fix",
            result.definition.nodes
                .first { it.id == "Task_1" }
                .name,
        )
        assertEquals(
            "start",
            result.definition.sequences
                .first { it.id == "Flow_1" }
                .name,
        )
    }

    @Test
    fun `first invalid operation stops processing and returns failure`() {
        val patch =
            BpmnRepairPatch(
                operations =
                listOf(
                    BpmnPatchOperation(BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Bad_Id", name = "fix"),
                    BpmnPatchOperation(BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Task_1", name = "Good fix"),
                ),
            )
        assertIs<PatchApplicationResult.Failure>(applier.apply(baseDefinition, patch))
    }

    @Test
    fun `patch preserves stable ids on all other nodes`() {
        val patch = patch(BpmnPatchOperation(BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Task_1", name = "Updated"))
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(baseDefinition, patch))
        assertEquals(
            setOf("Start_1", "Task_1", "End_1"),
            result.definition.nodes
                .map { it.id }
                .toSet(),
        )
        assertEquals(
            setOf("Flow_1", "Flow_2"),
            result.definition.sequences
                .map { it.id }
                .toSet(),
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun patch(vararg ops: BpmnPatchOperation) = BpmnRepairPatch(operations = ops.toList())

    private fun convergingGatewayDefinition(gatewayName: String?) = BpmnDefinition(
        processId = "Process_1",
        processName = "Merge decisions",
        nodes =
        listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnUserTask("Task_1", "Do work"),
            BpmnUserTask("Task_2", "Do other work"),
            BpmnExclusiveGateway("Gateway_1", gatewayName),
            BpmnEndEvent("End_1", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_1", "Start_1", "Task_1"),
            BpmnEdge("Flow_2", "Start_1", "Task_2"),
            BpmnEdge("Flow_3", "Task_1", "Gateway_1"),
            BpmnEdge("Flow_4", "Task_2", "Gateway_1"),
            BpmnEdge("Flow_5", "Gateway_1", "End_1"),
        ),
    )

    private fun divergingGatewayDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Route decision",
        nodes =
        listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnExclusiveGateway("Gateway_1", "Is request valid?"),
            BpmnUserTask("Task_1", "Approve request"),
            BpmnUserTask("Task_2", "Reject request"),
            BpmnEndEvent("End_1", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_1", "Start_1", "Gateway_1"),
            BpmnEdge("Flow_2", "Gateway_1", "Task_1", name = "Valid"),
            BpmnEdge("Flow_3", "Gateway_1", "Task_2", name = "Invalid"),
            BpmnEdge("Flow_4", "Task_1", "End_1"),
            BpmnEdge("Flow_5", "Task_2", "End_1"),
        ),
    )
}
