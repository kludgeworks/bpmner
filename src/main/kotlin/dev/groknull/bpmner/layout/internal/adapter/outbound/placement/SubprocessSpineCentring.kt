/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.POSITION_EPSILON
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Gateway
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.StartEvent
import org.camunda.bpm.model.bpmn.instance.SubProcess
import org.camunda.bpm.model.xml.instance.ModelElementInstance

/**
 * Declared move and repair: subprocess happy-path Y-snap.
 *
 * Move snaps nodes on the subprocess happy-path spine to the subprocess vertical centre-Y,
 * and snaps outer nodes directly connected to the subprocess to the same centre-Y.
 *
 * Repair re-routes edges touching snapped nodes as clean orthogonal lines.
 */
internal object SubprocessSpineCentring {

    val Move: PlacementProcessor = PlacementProcessor { ctx ->
        val snappedNodes = mutableSetOf<String>()
        ctx.model.getModelElementsByType(SubProcess::class.java).forEach { sub ->
            val subRect = ctx.shapes[sub.id] ?: return@forEach
            val subCentreY = subRect.y + subRect.h / 2.0

            // 1. Inner spine: snap Y to subprocess vertical midline.
            subSpineIds(sub, ctx.skeleton.loopBackFlowIds).forEach { id ->
                if (id !in snappedNodes) {
                    snapToCentreY(id, subCentreY, ctx)
                    snappedNodes.add(id)
                }
            }

            // 2. Outer nodes directly connected to the subprocess.
            ctx.model.getModelElementsByType(SequenceFlow::class.java).forEach { sf ->
                val srcId = sf.source?.id
                val tgtId = sf.target?.id
                when {
                    srcId == sub.id && tgtId != null -> {
                        if (tgtId !in snappedNodes) {
                            snapToCentreY(tgtId, subCentreY, ctx)
                            snappedNodes.add(tgtId)
                        }
                    }
                    tgtId == sub.id && srcId != null -> {
                        if (srcId !in snappedNodes) {
                            snapToCentreY(srcId, subCentreY, ctx)
                            snappedNodes.add(srcId)
                        }
                    }
                }
            }
        }
    }

    val Repair: PlacementProcessor = PlacementProcessor { ctx ->
        val centredNodes = ctx.moves
            .filter { it.value.owner == "SubprocessSpineCentring" }
            .keys
        if (centredNodes.isEmpty()) return@PlacementProcessor

        ctx.model.getModelElementsByType(SequenceFlow::class.java).forEach { sf ->
            val srcId = sf.source?.id ?: return@forEach
            val tgtId = sf.target?.id ?: return@forEach
            val srcSnapped = srcId in centredNodes
            val tgtSnapped = tgtId in centredNodes
            if (!srcSnapped && !tgtSnapped) return@forEach
            if (sf.id !in ctx.edges) return@forEach
            if (sf.id in ctx.skeleton.loopBackFlowIds) return@forEach
            // Skip subprocess exit flows — already re-anchored.
            val srcElement = ctx.model.getModelElementById<ModelElementInstance>(srcId)
            if (srcElement is SubProcess) return@forEach
            val srcRect = ctx.shapes[srcId] ?: return@forEach
            val tgtRect = ctx.shapes[tgtId] ?: return@forEach
            val srcCy = srcRect.y + srcRect.h / 2.0
            val tgtCy = tgtRect.y + tgtRect.h / 2.0
            val srcRight = srcRect.x + srcRect.w
            ctx.edges[sf.id] = if (kotlin.math.abs(srcCy - tgtCy) <= POSITION_EPSILON) {
                listOf(Point(srcRight, srcCy), Point(tgtRect.x, tgtCy))
            } else {
                val midX = srcRight + (tgtRect.x - srcRight) / 2.0
                listOf(
                    Point(srcRight, srcCy),
                    Point(midX, srcCy),
                    Point(midX, tgtCy),
                    Point(tgtRect.x, tgtCy),
                )
            }
        }
    }

    private fun snapToCentreY(id: String, targetCy: Double, ctx: PlacementContext) {
        val r = ctx.shapes[id] ?: return
        val newY = targetCy - r.h / 2.0
        if (kotlin.math.abs(newY - r.y) > POSITION_EPSILON) {
            ctx.shapes[id] = r.copy(y = newY)
            ctx.moves[id] = MoveRecord("SubprocessSpineCentring", 0.0, newY - r.y)
        }
    }

    /**
     * Spine node IDs in a subprocess: start events, end events, gateways, and tasks on the single
     * straight-through path (predecessor gateway has out-degree = 1, excluding loop-back edges).
     *
     * Branch tasks (predecessor gateway out-degree > 1) are excluded.
     */
    private fun subSpineIds(sub: SubProcess, loopBackFlowIds: Set<String>): Set<String> {
        val flows = sub.flowElements.filterIsInstance<SequenceFlow>()
            .filterNot { it.id in loopBackFlowIds }
        val outDegree = mutableMapOf<String, Int>()
        val inDegree = mutableMapOf<String, Int>()
        val predOf = mutableMapOf<String, String>()
        flows.forEach { sf ->
            val s = sf.source?.id ?: return@forEach
            val t = sf.target?.id ?: return@forEach
            outDegree[s] = (outDegree[s] ?: 0) + 1
            inDegree[t] = (inDegree[t] ?: 0) + 1
            predOf[t] = s
        }
        return sub.flowElements
            .filterIsInstance<FlowNode>()
            .filter { isSpineNode(it, inDegree, outDegree, predOf) }
            .mapTo(mutableSetOf()) { it.id }
    }

    private fun isSpineNode(
        node: FlowNode,
        inDegree: Map<String, Int>,
        outDegree: Map<String, Int>,
        predOf: Map<String, String>,
    ): Boolean {
        if (node is StartEvent || node is EndEvent || node is Gateway) return true
        // A node with no incoming non-loopback edges is a cycle entry point and belongs on the spine.
        if ((inDegree[node.id] ?: 0) == 0) return true
        if ((inDegree[node.id] ?: 0) != 1) return false
        val predId = predOf[node.id] ?: return false
        return (outDegree[predId] ?: 0) == 1
    }
}
