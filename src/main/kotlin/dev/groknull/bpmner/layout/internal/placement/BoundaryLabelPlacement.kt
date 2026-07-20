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
            val routeBottom = ctx.model.getModelElementsByType(SequenceFlow::class.java)
                .filter { it.source?.id == boundary.id }
                .flatMap { ctx.edges[it.id].orEmpty() }
                .maxOfOrNull { it.y }
            ctx.labels[boundary.id] = Rect(
                shape.x + shape.w / 2.0 - width / 2.0,
                maxOf(shape.y + shape.h, routeBottom ?: Double.NEGATIVE_INFINITY) + LABEL_GAP_BELOW,
                width,
                height,
            )
        }

        boundaries.mapNotNull { it.attachedTo?.id }.distinct().forEach { hostId ->
            val hostLabel = ctx.labels[hostId] ?: return@forEach
            val bottom = boundaries.filter { it.attachedTo?.id == hostId }.maxOfOrNull { boundary ->
                ctx.labels[boundary.id]?.let { it.y + it.h }
                    ?: ctx.shapes[boundary.id]?.let { it.y + it.h + LABEL_GAP_BELOW }
                    ?: Double.NEGATIVE_INFINITY
            } ?: return@forEach
            if (hostLabel.y < bottom) ctx.labels[hostId] = hostLabel.copy(y = bottom)
        }
    }
}
