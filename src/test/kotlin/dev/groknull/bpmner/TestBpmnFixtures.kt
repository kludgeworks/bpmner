/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.OwnedElementGraph

/**
 * Shared test fixtures for BPMN definitions and graphs to prevent duplication.
 */
object TestBpmnFixtures {
    /**
     * Creates a standard three-node BPMN definition (Start -> Task -> End).
     */
    @Suppress("LongParameterList")
    fun testBpmnDefinition(
        processId: String = "Process_MakeToast",
        processName: String = "Make toast",
        nodeType: NodeType = NodeType.SERVICE_TASK,
        startName: String = "Order received",
        taskName: String = "Toast bread",
        endName: String = "Toast served",
    ) = BpmnDefinition(
        processId = processId,
        processName = processName,
        nodes =
            listOf(
                BpmnNode("StartEvent_1", startName, NodeType.START_EVENT),
                BpmnNode("Task_1", taskName, nodeType),
                BpmnNode("EndEvent_1", endName, NodeType.END_EVENT),
            ),
        sequences =
            listOf(
                BpmnEdge("Flow_1", "StartEvent_1", "Task_1"),
                BpmnEdge("Flow_2", "Task_1", "EndEvent_1"),
            ),
    )

    /**
     * Creates a [LaidOutProcessGraph] for the given definition.
     */
    fun testLaidOutGraph(
        definition: BpmnDefinition,
        withOwnership: Boolean = false,
    ): LaidOutProcessGraph {
        val owner = if (withOwnership) "phase:main" else null
        val objectOwners =
            buildMap {
                if (owner != null) {
                    put("process", owner)
                    definition.nodes.forEach { put("nodes[id=${it.id}]", owner) }
                    definition.sequences.forEach { put("sequences[id=${it.id}]", owner) }
                }
            }
        val composed =
            ComposedProcessGraph(
                definition = definition,
                objectOwnersByObjectRef = objectOwners,
            )
        val elementOwners =
            buildMap {
                if (owner != null) {
                    put(definition.processId, owner)
                    definition.nodes.forEach { put(it.id, owner) }
                    definition.sequences.forEach { put(it.id, owner) }
                }
            }
        return LaidOutProcessGraph(OwnedElementGraph(composed, elementOwners, objectOwners), definition)
    }
}
