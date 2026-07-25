/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.camunda.bpm.model.bpmn.Bpmn
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import kotlin.test.assertFalse

/**
 * Acyclicity invariant for AD-622-15: the graph handed to ELK must be acyclic by construction —
 * every back-edge is emitted reversed by [BpmnToElkMapper] — root and every nested compound graph
 * (the compound case is the whole point: `CYCLE_BREAKING_STRATEGY`'s default `GREEDY` breaker
 * reverses an arbitrary edge silently if a cycle slips through). Without this check, a missed
 * back-edge's only symptom is a subtly wrong picture.
 */
class AcyclicElkGraphTest {

    @ParameterizedTest(name = "ELK skeleton is acyclic, root and nested: {0}")
    @ValueSource(
        strings = [
            "representative-process",
            "explicit-cycle",
            "long-labels",
            "annotation-and-group",
            "boundary-timer-task",
            "boundary-on-subprocess",
            "boundary-error-task",
            "boundary-multi",
            "subprocess-flat",
            "subprocess-loop",
            "subprocess-branch",
            "subprocess-nested",
            "subprocess-no-start-cycle",
            "subprocess-sequential-sharing",
            "collab-lanes",
            "collab-two-pools",
            "collab-blackbox",
            "collab-msg-endpoint",
            "collab-msg-label",
            "collab-subprocess",
            "collab-bioc",
            "collab-lanes-loopback",
            "miwg-a2-1",
            "miwg-a3-0",
        ],
    )
    fun `no directed cycle in any container of the mapped ELK skeleton`(fixture: String) {
        val input = load("layout-fixtures/$fixture.bpmn")
        val model = Bpmn.readModelFromStream(ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)))
        val skeleton = BpmnToElkMapper.map(model)

        val edgesByContainer = skeleton.edgeMap.values.groupBy { it.containingNode }
        edgesByContainer.forEach { (container, edges) ->
            assertFalse(
                hasCycle(edges),
                "[$fixture] container '${container?.identifier ?: "root"}' has a directed cycle among " +
                    "${edges.map { it.identifier }}",
            )
        }
    }

    /** Iterative DFS cycle detection, mirroring [BpmnToElkMapper]'s own `findLoopBackEdges` shape. */
    @Suppress("NestedBlockDepth")
    private fun hasCycle(edges: List<ElkEdge>): Boolean {
        val successors = mutableMapOf<ElkNode, MutableList<ElkNode>>()
        edges.forEach { edge ->
            val source = edge.sources.firstOrNull() as? ElkNode ?: return@forEach
            val target = edge.targets.firstOrNull() as? ElkNode ?: return@forEach
            successors.getOrPut(source) { mutableListOf() }.add(target)
        }

        val visited = mutableSetOf<ElkNode>()
        for (start in successors.keys) {
            if (start in visited) continue
            val ancestors = mutableSetOf<ElkNode>()
            val stack = ArrayDeque<Pair<ElkNode, Int>>()
            visited.add(start)
            ancestors.add(start)
            stack.addLast(start to 0)
            while (stack.isNotEmpty()) {
                val (node, idx) = stack.last()
                val neighbours = successors[node].orEmpty()
                if (idx >= neighbours.size) {
                    stack.removeLast()
                    ancestors.remove(node)
                } else {
                    stack[stack.size - 1] = node to (idx + 1)
                    val next = neighbours[idx]
                    when {
                        next in ancestors -> return true
                        next !in visited -> {
                            visited.add(next)
                            ancestors.add(next)
                            stack.addLast(next to 0)
                        }
                    }
                }
            }
        }
        return false
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("Resource not found: $resource")
}
