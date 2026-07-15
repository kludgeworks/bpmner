/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.SequenceFlow

/**
 * Pipeline entry 8 — ELK copy (pure): copy ELK routing sections verbatim for ordinary sequence flows.
 *
 * Postcondition: every ordinary (non-boundary-source, not already in [PlacementContext.edges])
 * sequence flow has its ELK-computed waypoints recorded in absolute canvas coordinates.
 * Flows already placed by [LoopBackEdgeArcs] or [ExceptionEdgeRoutes] are not overwritten.
 */
internal object SequenceEdgeElkCopy : PlacementProcessor {

    override fun process(ctx: PlacementContext) {
        val boundaryIds = ctx.model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }

        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.source?.id !in boundaryIds && it.id !in ctx.edges }
            .sortedBy { it.id }
            .forEach { sf ->
                val elkEdge = ctx.skeleton.edgeMap[sf.id] ?: return@forEach
                val section = elkEdge.sections.firstOrNull() ?: return@forEach
                val (ox, oy) = BpmnPlacementPass.edgeContainerOffset(elkEdge)
                val waypoints = mutableListOf(Point(section.startX + ox, section.startY + oy))
                section.bendPoints.mapTo(waypoints) { Point(it.x + ox, it.y + oy) }
                waypoints.add(Point(section.endX + ox, section.endY + oy))
                ctx.edges[sf.id] = waypoints
            }
    }
}
