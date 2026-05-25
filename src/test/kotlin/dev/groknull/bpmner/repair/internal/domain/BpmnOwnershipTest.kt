/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.withUpdatedDefinition
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnPatchApplier
import dev.groknull.bpmner.repair.internal.domain.handlers.BypassGatewayHandler
import dev.groknull.bpmner.repair.internal.domain.handlers.InsertConvergingGatewayHandler
import dev.groknull.bpmner.repair.internal.domain.handlers.SplitJoinForkGatewayHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BpmnOwnershipTest {
    private val applier = BpmnPatchApplier()
    private val splitJoinFork = SplitJoinForkGatewayHandler()
    private val insertConverging = InsertConvergingGatewayHandler()
    private val bypassGateway = BypassGatewayHandler()

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun baseGraph(): LaidOutProcessGraph {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Test",
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
        val objectOwners =
            buildMap {
                put("process", "phase:main")
                definition.nodes.forEach { put("nodes[id=${it.id}]", "phase:main") }
                definition.sequences.forEach { put("sequences[id=${it.id}]", "phase:main") }
            }
        val elementOwners =
            buildMap {
                put(definition.processId, "phase:main")
                definition.nodes.forEach { node ->
                    put(node.id, "phase:main")
                    put("${node.id}_di", "phase:main")
                }
                definition.sequences.forEach { edge ->
                    put(edge.id, "phase:main")
                    put("${edge.id}_di", "phase:main")
                }
            }
        val composedGraph =
            ComposedProcessGraph(
                definition = definition,
                objectOwnersByObjectRef = objectOwners,
            )
        val ownedGraph =
            OwnedElementGraph(
                composedGraph = composedGraph,
                elementOwnersByElementId = elementOwners,
                objectOwnersByObjectRef = objectOwners,
            )
        return LaidOutProcessGraph(ownedGraph = ownedGraph, definition = definition)
    }

    // -------------------------------------------------------------------------
    // validateOwnership
    // -------------------------------------------------------------------------

    @Test
    fun `validateOwnership clean graph returns empty`() {
        assertTrue(baseGraph().validateOwnership().isEmpty())
    }

    @Test
    fun `validateOwnership detects node missing from element owners`() {
        val graph = baseGraph()
        val extraNode = BpmnServiceTask("Orphan_1", "Orphan")
        val newDef = graph.definition.copy(nodes = graph.definition.nodes + extraNode)
        // Manually corrupt: update definition but do NOT reindex ownership
        val corruptGraph = LaidOutProcessGraph(ownedGraph = graph.ownedGraph, definition = newDef)
        val errors = corruptGraph.validateOwnership()
        assertTrue(errors.any { it.contains("Orphan_1") }, "Expected error for Orphan_1, got: $errors")
    }

    @Test
    fun `validateOwnership detects edge missing from element owners`() {
        val graph = baseGraph()
        val extraEdge = BpmnEdge("Flow_99", "Start_1", "End_1")
        val newDef = graph.definition.copy(sequences = graph.definition.sequences + extraEdge)
        val corruptGraph = LaidOutProcessGraph(ownedGraph = graph.ownedGraph, definition = newDef)
        val errors = corruptGraph.validateOwnership()
        assertTrue(errors.any { it.contains("Flow_99") }, "Expected error for Flow_99, got: $errors")
    }

    // -------------------------------------------------------------------------
    // withUpdatedDefinition — basic behaviour
    // -------------------------------------------------------------------------

    @Test
    fun `withUpdatedDefinition assigns owner to new node`() {
        val graph = baseGraph()
        val newNode = BpmnServiceTask("Task_2", "Extra task")
        val newDef = graph.definition.copy(nodes = graph.definition.nodes + newNode)
        val updated = graph.withUpdatedDefinition(newDef)
        assertNotNull(updated.ownerForElementId("Task_2"), "Task_2 must have an owner after reindex")
        assertNotNull(updated.ownerForElementId("Task_2_di"), "Task_2_di must have an owner after reindex")
    }

    @Test
    fun `withUpdatedDefinition assigns owner to new edge`() {
        val graph = baseGraph()
        val newEdge = BpmnEdge("Flow_3", "Start_1", "End_1")
        val newDef = graph.definition.copy(sequences = graph.definition.sequences + newEdge)
        val updated = graph.withUpdatedDefinition(newDef)
        assertNotNull(updated.ownerForElementId("Flow_3"), "Flow_3 must have an owner after reindex")
        assertNotNull(updated.ownerForElementId("Flow_3_di"), "Flow_3_di must have an owner after reindex")
    }

    @Test
    fun `withUpdatedDefinition preserves existing owners`() {
        val graph = baseGraph()
        val newDef = graph.definition.copy(processName = "Renamed")
        val updated = graph.withUpdatedDefinition(newDef)
        assertEquals("phase:main", updated.ownerForElementId("Start_1"))
        assertEquals("phase:main", updated.ownerForElementId("Task_1"))
        assertEquals("phase:main", updated.ownerForElementId("Flow_1"))
    }

    @Test
    fun `withUpdatedDefinition purges removed elements from owner map`() {
        val graph = baseGraph()
        val newDef =
            graph.definition.copy(
                nodes = graph.definition.nodes.filter { it.id != "Task_1" },
                sequences = graph.definition.sequences.filter { it.sourceRef != "Task_1" && it.targetRef != "Task_1" },
            )
        val updated = graph.withUpdatedDefinition(newDef)
        assertNull(updated.ownerForElementId("Task_1"), "Task_1 must be absent after removal")
        assertNull(updated.ownerForElementId("Task_1_di"), "Task_1_di must be absent after removal")
    }

    @Test
    fun `withUpdatedDefinition syncs composedGraph definition`() {
        val graph = baseGraph()
        val newDef = graph.definition.copy(processName = "Synced")
        val updated = graph.withUpdatedDefinition(newDef)
        assertEquals("Synced", updated.ownedGraph.composedGraph.definition.processName)
    }

    @Test
    fun `withUpdatedDefinition result passes validateOwnership`() {
        val graph = baseGraph()
        val newNode = BpmnUserTask("Task_2", "Extra")
        val newEdge = BpmnEdge("Flow_3", "Task_1", "Task_2")
        val newDef =
            graph.definition.copy(
                nodes = graph.definition.nodes + newNode,
                sequences = graph.definition.sequences + newEdge,
            )
        val updated = graph.withUpdatedDefinition(newDef)
        assertTrue(updated.validateOwnership().isEmpty(), "Expected clean ownership after reindex")
    }

    // -------------------------------------------------------------------------
    // Topology repair integration — ownership after gateway split/insert/remove
    // -------------------------------------------------------------------------

    @Test
    fun `withUpdatedDefinition after join-fork gateway split assigns owner to new gateway and edge`() {
        val definition = joinForkDefinition()
        val graph = graphFor(definition)
        val patch =
            BpmnRepairPatch(
                operations = splitJoinFork.buildPatch(definition, "Gateway_1"),
                reason = "test",
            )
        val newDef = (applier.apply(definition, patch) as PatchApplicationResult.Success).definition
        val updated = graph.withUpdatedDefinition(newDef)
        val newNodeId = newDef.nodes.first { it.id !in definition.nodes.map { n -> n.id } }.id
        assertNotNull(updated.ownerForElementId(newNodeId), "Inserted join gateway must have an owner")
        assertTrue(updated.validateOwnership().isEmpty(), "Ownership must be complete after join-fork repair")
    }

    @Test
    fun `withUpdatedDefinition after fake-join repair assigns owner to inserted gateway`() {
        val definition = fakeJoinDefinition()
        val graph = graphFor(definition)
        val patch =
            BpmnRepairPatch(
                operations = insertConverging.buildPatch(definition, "Task_1"),
                reason = "test",
            )
        val newDef = (applier.apply(definition, patch) as PatchApplicationResult.Success).definition
        val updated = graph.withUpdatedDefinition(newDef)
        assertTrue(updated.validateOwnership().isEmpty(), "Ownership must be complete after fake-join repair")
    }

    @Test
    fun `withUpdatedDefinition after superfluous gateway removal purges gateway from owners`() {
        val definition = superfluousGatewayDefinition()
        val graph = graphFor(definition)
        val patch =
            BpmnRepairPatch(
                operations = bypassGateway.buildPatch(definition, "Gateway_1"),
                reason = "test",
            )
        val newDef = (applier.apply(definition, patch) as PatchApplicationResult.Success).definition
        val updated = graph.withUpdatedDefinition(newDef)
        assertNull(updated.ownerForElementId("Gateway_1"), "Removed gateway must no longer have an owner")
        assertTrue(updated.validateOwnership().isEmpty(), "Ownership must be clean after superfluous gateway removal")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun graphFor(definition: BpmnDefinition): LaidOutProcessGraph {
        val objectOwners =
            buildMap {
                put("process", "phase:main")
                definition.nodes.forEach { put("nodes[id=${it.id}]", "phase:main") }
                definition.sequences.forEach { put("sequences[id=${it.id}]", "phase:main") }
            }
        val elementOwners =
            buildMap {
                put(definition.processId, "phase:main")
                definition.nodes.forEach { node ->
                    put(node.id, "phase:main")
                    put("${node.id}_di", "phase:main")
                }
                definition.sequences.forEach { edge ->
                    put(edge.id, "phase:main")
                    put("${edge.id}_di", "phase:main")
                }
            }
        val composedGraph =
            ComposedProcessGraph(
                definition = definition,
                objectOwnersByObjectRef = objectOwners,
            )
        val ownedGraph =
            OwnedElementGraph(
                composedGraph = composedGraph,
                elementOwnersByElementId = elementOwners,
                objectOwnersByObjectRef = objectOwners,
            )
        return LaidOutProcessGraph(ownedGraph = ownedGraph, definition = definition)
    }

    private fun joinForkDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Join Fork",
        nodes =
        listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnStartEvent("Start_2", "Trigger"),
            BpmnExclusiveGateway("Gateway_1", "Route?"),
            BpmnUserTask("Task_1", "Handle A"),
            BpmnUserTask("Task_2", "Handle B"),
            BpmnEndEvent("End_1", "End"),
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

    private fun fakeJoinDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Fake Join",
        nodes =
        listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnStartEvent("Start_2", "Trigger"),
            BpmnUserTask("Task_1", "Do work"),
            BpmnEndEvent("End_1", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_1", "Start_1", "Task_1"),
            BpmnEdge("Flow_2", "Start_2", "Task_1"),
            BpmnEdge("Flow_3", "Task_1", "End_1"),
        ),
    )

    private fun superfluousGatewayDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Superfluous Gateway",
        nodes =
        listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnExclusiveGateway("Gateway_1", null),
            BpmnUserTask("Task_1", "Do work"),
            BpmnEndEvent("End_1", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_1", "Start_1", "Gateway_1"),
            BpmnEdge("Flow_2", "Gateway_1", "Task_1"),
            BpmnEdge("Flow_3", "Task_1", "End_1"),
        ),
    )
}
