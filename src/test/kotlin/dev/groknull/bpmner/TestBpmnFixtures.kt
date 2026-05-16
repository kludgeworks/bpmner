/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner

import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnWaypoint
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
                BpmnNode("StartEvent_1", startName, NodeType.START_EVENT, BpmnBounds(80.0, 120.0, 36.0, 36.0)),
                BpmnNode("Task_1", taskName, nodeType, BpmnBounds(180.0, 98.0, 100.0, 80.0)),
                BpmnNode("EndEvent_1", endName, NodeType.END_EVENT, BpmnBounds(320.0, 120.0, 36.0, 36.0)),
            ),
        sequences =
            listOf(
                BpmnEdge(
                    "Flow_1",
                    "StartEvent_1",
                    "Task_1",
                    waypoints = listOf(BpmnWaypoint(116.0, 138.0), BpmnWaypoint(180.0, 138.0)),
                ),
                BpmnEdge(
                    "Flow_2",
                    "Task_1",
                    "EndEvent_1",
                    waypoints = listOf(BpmnWaypoint(280.0, 138.0), BpmnWaypoint(320.0, 138.0)),
                ),
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
                    definition.nodes.forEach {
                        put(it.id, owner)
                        put("${it.id}_di", owner)
                    }
                    definition.sequences.forEach {
                        put(it.id, owner)
                        put("${it.id}_di", owner)
                    }
                }
            }
        return LaidOutProcessGraph(OwnedElementGraph(composed, elementOwners, objectOwners), definition)
    }
}
