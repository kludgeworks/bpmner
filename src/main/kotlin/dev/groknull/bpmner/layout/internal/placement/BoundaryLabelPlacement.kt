/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.LABEL_GAP_BELOW
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.LABEL_WIDTH
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.SequenceFlow

/** Applies the retained BPMN-specific label rule for boundary events and their hosts. */
internal object BoundaryLabelPlacement : PlacementProcessor {

    override fun process(ctx: PlacementContext) {
        val boundaries = ctx.model.getModelElementsByType(BoundaryEvent::class.java)
        boundaries.filter { !it.name.isNullOrBlank() }.sortedBy { it.id }.forEach { boundary ->
            val shape = ctx.shapes[boundary.id] ?: return@forEach
            val (width, height) = BpmnPlacementPass.estimateLabelDimensions(boundary.name!!, LABEL_WIDTH)
            ctx.labels[boundary.id] = Rect(
                shape.x + shape.w / 2.0 - width / 2.0,
                maxOf(shape.y + shape.h, routeBottom(boundary, ctx) ?: Double.NEGATIVE_INFINITY) + LABEL_GAP_BELOW,
                width,
                height,
            )
        }

        boundaries.mapNotNull { it.attachedTo?.id }.distinct().forEach { hostId ->
            val hostLabel = ctx.labels[hostId] ?: return@forEach
            val bottom = boundaries.filter { it.attachedTo?.id == hostId }.maxOfOrNull { boundary ->
                ctx.labels[boundary.id]?.let { it.y + it.h }
                    ?: unnamedBoundaryClearance(boundary, ctx)
                    ?: Double.NEGATIVE_INFINITY
            } ?: return@forEach
            if (hostLabel.y < bottom) ctx.labels[hostId] = hostLabel.copy(y = bottom)
        }
    }

    /**
     * Clearance below an unnamed boundary's own shape *and* its outgoing exception route: mirrors
     * the named-boundary label's own `routeBottom` floor (above) so a host label can't sit above a
     * route the unnamed boundary is still using, just because that boundary has no label of its own.
     */
    private fun unnamedBoundaryClearance(boundary: BoundaryEvent, ctx: PlacementContext): Double? {
        val shape = ctx.shapes[boundary.id] ?: return null
        return maxOf(shape.y + shape.h, routeBottom(boundary, ctx) ?: Double.NEGATIVE_INFINITY) + LABEL_GAP_BELOW
    }

    /** The lowest y any of [boundary]'s outgoing exception routes reach, if it has any. */
    private fun routeBottom(boundary: BoundaryEvent, ctx: PlacementContext): Double? =
        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.source?.id == boundary.id }
            .flatMap { ctx.edges[it.id].orEmpty() }
            .maxOfOrNull { it.y }
}
