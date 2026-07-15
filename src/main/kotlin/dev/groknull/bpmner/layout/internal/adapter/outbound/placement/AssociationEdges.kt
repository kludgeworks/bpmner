/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import org.camunda.bpm.model.bpmn.instance.Association

/**
 * Pipeline entry 14 — decoration: straight border-to-border connectors for associations.
 *
 * Postcondition: every [Association] has a two-waypoint route in [PlacementContext.edges]
 * connecting the nearest border points of its source and target shapes.
 */
internal object AssociationEdges : PlacementProcessor {

    override fun process(ctx: PlacementContext) {
        ctx.model.getModelElementsByType(Association::class.java)
            .sortedBy { it.id }
            .forEach { assoc ->
                val sourceRect = ctx.shapes[assoc.source?.id] ?: return@forEach
                val targetRect = ctx.shapes[assoc.target?.id] ?: return@forEach
                ctx.edges[assoc.id] = listOf(
                    borderPoint(sourceRect, targetRect),
                    borderPoint(targetRect, sourceRect),
                )
            }
    }

    private fun borderPoint(rect: Rect, toward: Rect): Point {
        val cx = rect.x + rect.w / 2.0
        val cy = rect.y + rect.h / 2.0
        val tx = toward.x + toward.w / 2.0
        val ty = toward.y + toward.h / 2.0
        val dx = tx - cx
        val dy = ty - cy
        if (dx == 0.0 && dy == 0.0) return Point(cx, cy)
        val hw = rect.w / 2.0
        val hh = rect.h / 2.0
        val scaleX = if (dx != 0.0) hw / kotlin.math.abs(dx) else Double.MAX_VALUE
        val scaleY = if (dy != 0.0) hh / kotlin.math.abs(dy) else Double.MAX_VALUE
        val scale = kotlin.math.min(scaleX, scaleY)
        return Point(cx + dx * scale, cy + dy * scale)
    }
}
