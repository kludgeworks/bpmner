/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import org.camunda.bpm.model.bpmn.instance.SequenceFlow

/**
 * Copies ELK routing sections verbatim for sequence flows, including exception edges sourced at
 * a boundary event's port (AD-622-08) — ELK routes those the same way it routes any other edge.
 *
 * Every sequence flow not already in [PlacementContext.edges] has its ELK-computed waypoints
 * recorded in absolute canvas coordinates. Loop-back flows ([PlacementContext.skeleton]'s
 * `reversedFlowIds`) were mapped to ELK with source and target swapped (AD-622-15); their
 * waypoint list is reversed here, immediately before storing, so the BPMN-DI direction matches
 * the flow's real source/target — every point is byte-identical, only the sequence order flips,
 * mirroring ELK's own `ReversedEdgeRestorer`.
 */
internal object SequenceEdgeElkCopy : PlacementProcessor {

    override fun process(ctx: PlacementContext) {
        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.id !in ctx.edges }
            .sortedBy { it.id }
            .forEach { sf ->
                val elkEdge = ctx.skeleton.edgeMap[sf.id] ?: return@forEach
                val section = elkEdge.sections.firstOrNull() ?: return@forEach
                val (ox, oy) = BpmnPlacementPass.edgeContainerOffset(elkEdge)
                val waypoints = mutableListOf(Point(section.startX + ox, section.startY + oy))
                section.bendPoints.mapTo(waypoints) { Point(it.x + ox, it.y + oy) }
                waypoints.add(Point(section.endX + ox, section.endY + oy))
                ctx.edges[sf.id] = if (sf.id in ctx.skeleton.reversedFlowIds) waypoints.asReversed() else waypoints
            }
    }
}
