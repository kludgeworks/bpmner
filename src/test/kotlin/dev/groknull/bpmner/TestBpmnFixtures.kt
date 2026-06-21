/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import dev.groknull.bpmner.bpmn.BpmnNode
import dev.groknull.bpmner.bpmn.internal.model.BpmnDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnEdge
import dev.groknull.bpmner.bpmn.internal.model.BpmnEndEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnServiceTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnStartEvent
import dev.groknull.bpmner.bpmn.internal.model.ComposedProcessGraph
import dev.groknull.bpmner.bpmn.internal.model.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.internal.model.OwnedElementGraph

/**
 * Shared test fixtures for BPMN definitions and graphs to prevent duplication.
 */
object TestBpmnFixtures {
    /**
     * Creates a standard three-node BPMN definition (Start -> Task -> End). The middle
     * task is configurable so tests can vary between user and service tasks; callers
     * pass the constructed [BpmnNode] directly rather than discriminating by enum.
     */
    fun testBpmnDefinition(
        processId: String = "Process_MakeToast",
        processName: String = "Make toast",
        task: BpmnNode = BpmnServiceTask("Task_1", "Toast bread"),
        startName: String = "Order received",
        endName: String = "Toast served",
    ) = BpmnDefinition(
        processId = processId,
        processName = processName,
        nodes =
        listOf(
            BpmnStartEvent("StartEvent_1", startName),
            task,
            BpmnEndEvent("EndEvent_1", endName),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_1", "StartEvent_1", task.id),
            BpmnEdge("Flow_2", task.id, "EndEvent_1"),
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
