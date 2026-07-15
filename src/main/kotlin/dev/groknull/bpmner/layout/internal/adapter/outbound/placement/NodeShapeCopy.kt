/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.SubProcess

/**
 * Pipeline entry 1 — ELK section copy (pure).
 *
 * Postcondition: every non-boundary flow-node shape in [PlacementContext.shapes] holds the
 * absolute ELK coordinates for that node, and every expanded SubProcess ID is in
 * [PlacementContext.expanded]. No move is ledgered (AD-557-11: ELK bounds are immutable).
 */
internal object NodeShapeCopy : PlacementProcessor {

    override fun process(ctx: PlacementContext) {
        for (
        flowNode in ctx.model.getModelElementsByType(FlowNode::class.java)
            .filter { it !is BoundaryEvent }
            .sortedBy { it.id }
        ) {
            val elkNode = ctx.skeleton.nodeMap[flowNode.id] ?: continue
            val (ax, ay) = BpmnPlacementPass.absolutePosition(elkNode)
            ctx.shapes[flowNode.id] = Rect(ax, ay, elkNode.width, elkNode.height)
            if (flowNode is SubProcess) ctx.expanded.add(flowNode.id)
        }
    }
}
