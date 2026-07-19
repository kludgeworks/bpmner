/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.BpmnAutoLayoutException
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.SubProcess

/**
 * Verbatim copy of ELK node coordinates.
 *
 * Every non-boundary flow-node shape in [PlacementContext.shapes] holds the
 * absolute ELK coordinates for that node, and every expanded SubProcess ID is in
 * [PlacementContext.expanded].
 */
internal object NodeShapeCopy : PlacementProcessor {

    override fun process(ctx: PlacementContext) {
        for (
        flowNode in ctx.model.getModelElementsByType(FlowNode::class.java)
            .filter { it !is BoundaryEvent }
            .sortedBy { it.id }
        ) {
            val elkNode = ctx.skeleton.nodeMap[flowNode.id]
                ?: throw BpmnAutoLayoutException("ELK layout: flow node '${flowNode.id}' has no corresponding ELK node")
            val (ax, ay) = BpmnPlacementPass.absolutePosition(elkNode)
            ctx.shapes[flowNode.id] = Rect(ax, ay, elkNode.width, elkNode.height)
            if (flowNode is SubProcess) ctx.expanded.add(flowNode.id)
        }
    }
}
