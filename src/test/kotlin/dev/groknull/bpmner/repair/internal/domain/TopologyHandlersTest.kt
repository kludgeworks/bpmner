/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnPatchApplier
import dev.groknull.bpmner.repair.internal.domain.handlers.BypassGatewayHandler
import dev.groknull.bpmner.repair.internal.domain.handlers.ConvergingGatewayClearNameHandler
import dev.groknull.bpmner.repair.internal.domain.handlers.InsertConvergingGatewayHandler
import dev.groknull.bpmner.repair.internal.domain.handlers.SplitJoinForkGatewayHandler
import dev.groknull.bpmner.validation.internal.domain.BpmnDefinitionValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopologyHandlersTest {
    private val applier = BpmnPatchApplier()
    private val validator = BpmnDefinitionValidator()

    private val splitJoinFork = SplitJoinForkGatewayHandler()
    private val insertConverging = InsertConvergingGatewayHandler()
    private val bypassGateway = BypassGatewayHandler()
    private val clearConvergingName = ConvergingGatewayClearNameHandler()

    // ---- SplitJoinForkGatewayHandler --------------------------------------

    @Test
    fun `splitJoinFork adds converging gateway before mixed gateway`() {
        val definition = joinForkDefinition()
        val ops = splitJoinFork.buildPatch(definition, "Gateway_1")

        assertTrue(ops.isNotEmpty())
        val addNode = ops.single { it.type == BpmnPatchOperationType.ADD_NODE }
        val addEdge = ops.single { it.type == BpmnPatchOperationType.ADD_EDGE }
        val replaceEdges = ops.filter { it.type == BpmnPatchOperationType.REPLACE_EDGE }

        assertEquals(NodeType.EXCLUSIVE_GATEWAY, addNode.node!!.type)
        assertNull(addNode.node!!.name, "Inserted converging gateway must have no name")
        assertEquals(addNode.node!!.id, addEdge.edge!!.sourceRef)
        assertEquals("Gateway_1", addEdge.edge!!.targetRef)
        assertEquals(2, replaceEdges.size)
        replaceEdges.forEach { op ->
            assertEquals(addNode.node!!.id, op.edge!!.targetRef)
        }
    }

    @Test
    fun `splitJoinFork produces valid definition`() {
        val definition = joinForkDefinition()
        val ops = splitJoinFork.buildPatch(definition, "Gateway_1")
        val result =
            assertIs<PatchApplicationResult.Success>(
                applier.apply(definition, BpmnRepairPatch(operations = ops, reason = "test")),
            )
        val errors = validator.validate(result.definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
    }

    @Test
    fun `splitJoinFork leaves original gateway with single incoming`() {
        val definition = joinForkDefinition()
        val ops = splitJoinFork.buildPatch(definition, "Gateway_1")
        val result =
            assertIs<PatchApplicationResult.Success>(
                applier.apply(definition, BpmnRepairPatch(operations = ops, reason = "test")),
            )
        val incomingToGw1 = result.definition.sequences.count { it.targetRef == "Gateway_1" }
        assertEquals(1, incomingToGw1, "Gateway_1 should have exactly 1 incoming after split")
    }

    @Test
    fun `splitJoinFork returns empty ops for non-mixed gateway`() {
        val definition = divergingGatewayDefinition()
        val ops = splitJoinFork.buildPatch(definition, "Gateway_1")
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `splitJoinFork returns empty ops for unknown element id`() {
        val definition = joinForkDefinition()
        val ops = splitJoinFork.buildPatch(definition, "NotPresent")
        assertTrue(ops.isEmpty())
    }

    // ---- InsertConvergingGatewayHandler -----------------------------------

    @Test
    fun `insertConverging adds gateway before task with multiple incoming`() {
        val definition = fakeJoinDefinition()
        val ops = insertConverging.buildPatch(definition, "Task_1")

        assertTrue(ops.isNotEmpty())
        val addNode = ops.single { it.type == BpmnPatchOperationType.ADD_NODE }
        val addEdge = ops.single { it.type == BpmnPatchOperationType.ADD_EDGE }
        val replaceEdges = ops.filter { it.type == BpmnPatchOperationType.REPLACE_EDGE }

        assertEquals(NodeType.EXCLUSIVE_GATEWAY, addNode.node!!.type)
        assertNull(addNode.node!!.name, "Inserted converging gateway must have no name")
        assertEquals(addNode.node!!.id, addEdge.edge!!.sourceRef)
        assertEquals("Task_1", addEdge.edge!!.targetRef)
        assertEquals(2, replaceEdges.size)
        replaceEdges.forEach { op ->
            assertEquals(addNode.node!!.id, op.edge!!.targetRef)
        }
    }

    @Test
    fun `insertConverging produces valid definition`() {
        val definition = fakeJoinDefinition()
        val ops = insertConverging.buildPatch(definition, "Task_1")
        val result =
            assertIs<PatchApplicationResult.Success>(
                applier.apply(definition, BpmnRepairPatch(operations = ops, reason = "test")),
            )
        val errors = validator.validate(result.definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
    }

    @Test
    fun `insertConverging leaves task with single incoming`() {
        val definition = fakeJoinDefinition()
        val ops = insertConverging.buildPatch(definition, "Task_1")
        val result =
            assertIs<PatchApplicationResult.Success>(
                applier.apply(definition, BpmnRepairPatch(operations = ops, reason = "test")),
            )
        val incomingToTask = result.definition.sequences.count { it.targetRef == "Task_1" }
        assertEquals(1, incomingToTask, "Task_1 should have exactly 1 incoming after repair")
    }

    @Test
    fun `insertConverging returns empty ops for single-incoming task`() {
        val definition = singleIncomingTaskDefinition()
        val ops = insertConverging.buildPatch(definition, "Task_1")
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `insertConverging returns empty ops for non-task node with multiple incoming`() {
        val definition = multiIncomingEndEventDefinition()
        val ops = insertConverging.buildPatch(definition, "End_1")
        assertTrue(ops.isEmpty())
    }

    // ---- BypassGatewayHandler ---------------------------------------------

    @Test
    fun `bypassGateway produces replace-remove-remove operations`() {
        val definition = superfluousGatewayDefinition()
        val ops = bypassGateway.buildPatch(definition, "Gateway_1")

        assertEquals(3, ops.size)
        assertEquals(BpmnPatchOperationType.REPLACE_EDGE, ops[0].type)
        assertEquals(BpmnPatchOperationType.REMOVE_EDGE, ops[1].type)
        assertEquals(BpmnPatchOperationType.REMOVE_NODE, ops[2].type)
        assertEquals("Gateway_1", ops[2].nodeId)
    }

    @Test
    fun `bypassGateway removes gateway and rewires flow`() {
        val definition = superfluousGatewayDefinition()
        val ops = bypassGateway.buildPatch(definition, "Gateway_1")
        val result =
            assertIs<PatchApplicationResult.Success>(
                applier.apply(definition, BpmnRepairPatch(operations = ops, reason = "test")),
            )

        assertNull(result.definition.nodes.firstOrNull { it.id == "Gateway_1" }, "Gateway should be removed")
        val incomingToTask = result.definition.sequences.filter { it.targetRef == "Task_1" }
        assertEquals(1, incomingToTask.size, "Task_1 should have exactly 1 incoming after bypass")
        assertEquals("Start_1", incomingToTask.first().sourceRef, "Incoming should come from Start_1")
    }

    @Test
    fun `bypassGateway produces valid definition`() {
        val definition = superfluousGatewayDefinition()
        val ops = bypassGateway.buildPatch(definition, "Gateway_1")
        val result =
            assertIs<PatchApplicationResult.Success>(
                applier.apply(definition, BpmnRepairPatch(operations = ops, reason = "test")),
            )
        val errors = validator.validate(result.definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
    }

    @Test
    fun `bypassGateway returns empty ops for diverging gateway`() {
        val definition = divergingGatewayDefinition()
        val ops = bypassGateway.buildPatch(definition, "Gateway_1")
        assertTrue(ops.isEmpty())
    }

    // ---- ConvergingGatewayClearNameHandler --------------------------------

    @Test
    fun `clearConvergingName clears name from named converging gateway`() {
        val definition = convergingNamedGatewayDefinition("Join")
        val ops = clearConvergingName.buildPatch(definition, "Gateway_1")

        assertEquals(1, ops.size)
        val op = ops.single()
        assertEquals(BpmnPatchOperationType.SET_NODE_NAME, op.type)
        assertEquals("Gateway_1", op.nodeId)
        assertNull(op.name, "Converging gateway name must be cleared to null")
    }

    @Test
    fun `clearConvergingName produces valid definition`() {
        val definition = convergingNamedGatewayDefinition("Join")
        val ops = clearConvergingName.buildPatch(definition, "Gateway_1")
        val result =
            assertIs<PatchApplicationResult.Success>(
                applier.apply(definition, BpmnRepairPatch(operations = ops, reason = "test")),
            )
        val errors = validator.validate(result.definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
        assertNull(
            result.definition.nodes
                .single { it.id == "Gateway_1" }
                .name,
        )
    }

    @Test
    fun `clearConvergingName returns empty ops for already unnamed converging gateway`() {
        val definition = convergingNamedGatewayDefinition(gatewayName = null)
        val ops = clearConvergingName.buildPatch(definition, "Gateway_1")
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `clearConvergingName returns empty ops for diverging gateway`() {
        val definition = divergingGatewayDefinition()
        val ops = clearConvergingName.buildPatch(definition, "Gateway_1")
        assertTrue(ops.isEmpty())
    }

    // ---- Handler-name invariants ------------------------------------------

    @Test
    fun `handler names match Pkl repair handler fields`() {
        assertEquals("splitJoinForkGateway", splitJoinFork.handlerName)
        assertEquals("insertConvergingGateway", insertConverging.handlerName)
        assertEquals("bypassGateway", bypassGateway.handlerName)
        assertEquals("clearConvergingGatewayName", clearConvergingName.handlerName)
    }

    @Test
    fun `splitJoinFork builds non-empty operations for valid input`() {
        val definition = joinForkDefinition()
        val ops = splitJoinFork.buildPatch(definition, "Gateway_1")
        assertNotNull(ops)
        assertTrue(ops.isNotEmpty())
    }

    // ---- Fixtures ----------------------------------------------------------

    private fun joinForkDefinition() =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Join Fork Process",
            nodes =
                listOf(
                    BpmnNode("Start_1", "Start", NodeType.START_EVENT),
                    BpmnNode("Start_2", "Trigger", NodeType.START_EVENT),
                    BpmnNode("Gateway_1", "Route?", NodeType.EXCLUSIVE_GATEWAY),
                    BpmnNode("Task_1", "Handle A", NodeType.USER_TASK),
                    BpmnNode("Task_2", "Handle B", NodeType.USER_TASK),
                    BpmnNode("End_1", "End", NodeType.END_EVENT),
                ),
            sequences =
                listOf(
                    BpmnEdge("Flow_1", "Start_1", "Gateway_1"),
                    BpmnEdge("Flow_2", "Start_2", "Gateway_1"),
                    BpmnEdge("Flow_3", "Gateway_1", "Task_1", name = "Path A"),
                    BpmnEdge("Flow_4", "Gateway_1", "Task_2", name = "Path B"),
                    BpmnEdge("Flow_5", "Task_1", "End_1"),
                    BpmnEdge("Flow_6", "Task_2", "End_1"),
                ),
        )

    private fun fakeJoinDefinition() =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Fake Join Process",
            nodes =
                listOf(
                    BpmnNode("Start_1", "Start", NodeType.START_EVENT),
                    BpmnNode("Start_2", "Trigger", NodeType.START_EVENT),
                    BpmnNode("Task_1", "Do work", NodeType.USER_TASK),
                    BpmnNode("End_1", "End", NodeType.END_EVENT),
                ),
            sequences =
                listOf(
                    BpmnEdge("Flow_1", "Start_1", "Task_1"),
                    BpmnEdge("Flow_2", "Start_2", "Task_1"),
                    BpmnEdge("Flow_3", "Task_1", "End_1"),
                ),
        )

    private fun singleIncomingTaskDefinition() =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Single Incoming",
            nodes =
                listOf(
                    BpmnNode("Start_1", "Start", NodeType.START_EVENT),
                    BpmnNode("Task_1", "Do work", NodeType.USER_TASK),
                    BpmnNode("End_1", "End", NodeType.END_EVENT),
                ),
            sequences =
                listOf(
                    BpmnEdge("Flow_1", "Start_1", "Task_1"),
                    BpmnEdge("Flow_2", "Task_1", "End_1"),
                ),
        )

    private fun multiIncomingEndEventDefinition() =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Multi Incoming End Event",
            nodes =
                listOf(
                    BpmnNode("Start_1", "Start", NodeType.START_EVENT),
                    BpmnNode("Start_2", "Trigger", NodeType.START_EVENT),
                    BpmnNode("End_1", "Done", NodeType.END_EVENT),
                ),
            sequences =
                listOf(
                    BpmnEdge("Flow_1", "Start_1", "End_1"),
                    BpmnEdge("Flow_2", "Start_2", "End_1"),
                ),
        )

    private fun superfluousGatewayDefinition() =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Superfluous Gateway Process",
            nodes =
                listOf(
                    BpmnNode("Start_1", "Start", NodeType.START_EVENT),
                    BpmnNode("Gateway_1", null, NodeType.EXCLUSIVE_GATEWAY),
                    BpmnNode("Task_1", "Do work", NodeType.USER_TASK),
                    BpmnNode("End_1", "End", NodeType.END_EVENT),
                ),
            sequences =
                listOf(
                    BpmnEdge("Flow_1", "Start_1", "Gateway_1"),
                    BpmnEdge("Flow_2", "Gateway_1", "Task_1"),
                    BpmnEdge("Flow_3", "Task_1", "End_1"),
                ),
        )

    private fun divergingGatewayDefinition() =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Diverging Process",
            nodes =
                listOf(
                    BpmnNode("Start_1", "Start", NodeType.START_EVENT),
                    BpmnNode(
                        "Gateway_1",
                        "Is valid?",
                        NodeType.EXCLUSIVE_GATEWAY,
                    ),
                    BpmnNode("Task_1", "Approve", NodeType.USER_TASK),
                    BpmnNode("Task_2", "Reject", NodeType.USER_TASK),
                    BpmnNode("End_1", "End", NodeType.END_EVENT),
                ),
            sequences =
                listOf(
                    BpmnEdge("Flow_1", "Start_1", "Gateway_1"),
                    BpmnEdge("Flow_2", "Gateway_1", "Task_1", name = "Yes"),
                    BpmnEdge("Flow_3", "Gateway_1", "Task_2", name = "No"),
                    BpmnEdge("Flow_4", "Task_1", "End_1"),
                    BpmnEdge("Flow_5", "Task_2", "End_1"),
                ),
        )

    private fun convergingNamedGatewayDefinition(gatewayName: String? = "Join") =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Converging Named Gateway Process",
            nodes =
                listOf(
                    BpmnNode("Start_1", "Path A", NodeType.START_EVENT),
                    BpmnNode("Start_2", "Path B", NodeType.START_EVENT),
                    BpmnNode(
                        "Gateway_1",
                        gatewayName,
                        NodeType.EXCLUSIVE_GATEWAY,
                    ),
                    BpmnNode("Task_1", "Continue", NodeType.USER_TASK),
                    BpmnNode("End_1", "End", NodeType.END_EVENT),
                ),
            sequences =
                listOf(
                    BpmnEdge("Flow_1", "Start_1", "Gateway_1"),
                    BpmnEdge("Flow_2", "Start_2", "Gateway_1"),
                    BpmnEdge("Flow_3", "Gateway_1", "Task_1"),
                    BpmnEdge("Flow_4", "Task_1", "End_1"),
                ),
        )
}
