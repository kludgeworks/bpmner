/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.SequenceFlow

/**
 * Re-anchors every sequence flow touched by [CollaborationFramePlacement]'s participant/lane
 * translations: a flow whose endpoints moved by the same vector is translated whole; one whose
 * endpoints moved by different vectors (or one endpoint didn't move at all) is re-routed between
 * their new positions instead.
 */
internal object SequenceFlowRepair {

    fun repair(translations: Map<String, Point>, ctx: PlacementContext) {
        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { flow -> flow.source?.id in translations || flow.target?.id in translations }
            .sortedBy { it.id }
            .forEach { flow ->
                val sourceVector = translations[flow.source?.id]
                val targetVector = translations[flow.target?.id]
                when {
                    sourceVector != null && sourceVector == targetVector -> translateFlow(flow, sourceVector, ctx)
                    flow.source is BoundaryEvent -> routeException(flow, ctx)
                    else -> routeSequence(flow, ctx)
                }
            }
    }

    /** Shifts an already-ELK-routed flow's waypoints and label by the same rigid vector as its endpoints. */
    private fun translateFlow(flow: SequenceFlow, vector: Point, ctx: PlacementContext) {
        ctx.edges[flow.id] = ctx.edges[flow.id]?.map { Point(it.x + vector.x, it.y + vector.y) } ?: return
        ctx.labels[flow.id]?.let { label -> ctx.labels[flow.id] = label.copy(x = label.x + vector.x, y = label.y + vector.y) }
    }

    /**
     * Re-anchors an exception edge after its lane/participant band shifted independently of its
     * handler: a deterministic drop-then-horizontal route from the boundary's new position to the
     * handler's new position, mirroring [routeSequence]'s own simple case.
     */
    private fun routeException(flow: SequenceFlow, ctx: PlacementContext) {
        val boundary = flow.source as? BoundaryEvent ?: return
        val source = ctx.shapes[boundary.id] ?: return
        val target = ctx.shapes[flow.target?.id] ?: return
        val startX = source.x + source.w / 2.0
        val startY = source.y + source.h
        val targetCy = target.y + target.h / 2.0
        val enterX = if (target.x >= startX) target.x else target.x + target.w
        ctx.edges[flow.id] = listOf(Point(startX, startY), Point(startX, targetCy), Point(enterX, targetCy))
        CollaborationFramePlacement.centerLabelOnRoute(flow.id, flow.name, ctx)
    }

    private fun routeSequence(flow: SequenceFlow, ctx: PlacementContext) {
        val source = ctx.shapes[flow.source?.id] ?: return
        val target = ctx.shapes[flow.target?.id] ?: return
        val sourceMiddleY = source.y + source.h / 2.0
        val targetMiddleY = target.y + target.h / 2.0
        if (sourceMiddleY == targetMiddleY) {
            ctx.edges[flow.id] = listOf(
                Point(source.x + source.w, sourceMiddleY),
                Point(target.x, targetMiddleY),
            )
            CollaborationFramePlacement.centerLabelOnRoute(flow.id, flow.name, ctx)
            return
        }

        val sourceAboveTarget = sourceMiddleY < targetMiddleY
        val start = Point(source.x + source.w / 2.0, if (sourceAboveTarget) source.y + source.h else source.y)
        val end = Point(target.x + target.w / 2.0, if (sourceAboveTarget) target.y else target.y + target.h)
        val bendY = (start.y + end.y) / 2.0
        ctx.edges[flow.id] = listOf(start, Point(start.x, bendY), Point(end.x, bendY), end)
        CollaborationFramePlacement.centerLabelOnRoute(flow.id, flow.name, ctx)
    }
}
