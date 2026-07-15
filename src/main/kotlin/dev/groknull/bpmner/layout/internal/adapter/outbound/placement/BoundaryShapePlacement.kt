/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.SequenceFlow

/**
 * Boundary event shapes on host bottom edge.
 *
 * Every boundary-event shape is placed on its host's BOTTOM edge, straddling
 * (centre on the edge), evenly distributed for multiple attachments.
 *
 * Minimum gap between adjacent boundary shapes on the same host edge: [BOUNDARY_MIN_GAP].
 */
internal object BoundaryShapePlacement : PlacementProcessor {

    private const val BOUNDARY_HALF = BpmnToElkMapper.EVENT_SIZE / 2.0
    private const val BOUNDARY_MIN_GAP = 8.0

    override fun process(ctx: PlacementContext) {
        // Map each boundary to its handler for ordering.
        val handlerOf = mutableMapOf<String, String>()
        ctx.model.getModelElementsByType(SequenceFlow::class.java).forEach { sf ->
            val src = sf.source?.id
            val tgt = sf.target?.id
            if (src != null && tgt != null) handlerOf[src] = tgt
        }

        val byHost = ctx.model.getModelElementsByType(BoundaryEvent::class.java)
            .groupBy { it.attachedTo?.id }

        for ((hostId, boundaries) in byHost.filterKeys { it != null }) {
            val hostNode = ctx.skeleton.nodeMap[hostId] ?: continue
            val hostRect = ctx.shapes[hostId] ?: run {
                val (hx, hy) = BpmnPlacementPass.absolutePosition(hostNode)
                Rect(hx, hy, hostNode.width, hostNode.height)
            }
            placeBoundariesOnHost(hostRect, boundaries, ctx.shapes, handlerOf)
        }
    }

    private fun placeBoundariesOnHost(
        hostRect: Rect,
        boundaries: List<BoundaryEvent>,
        shapes: MutableMap<String, Rect>,
        handlerOf: Map<String, String>,
    ) {
        val eventSize = BpmnToElkMapper.EVENT_SIZE
        val ordered = boundaries.sortedWith(
            compareBy({ be -> shapes[handlerOf[be.id]]?.y ?: Double.MAX_VALUE }, { it.id }),
        )
        val n = ordered.size
        val centreY = hostRect.y + hostRect.h
        val pitch = eventSize + BOUNDARY_MIN_GAP
        val evenStep = hostRect.w / (n + 1).toDouble()
        if (evenStep >= pitch) {
            ordered.forEachIndexed { index, be ->
                val centreX = hostRect.x + evenStep * (index + 1)
                shapes[be.id] = Rect(centreX - BOUNDARY_HALF, centreY - BOUNDARY_HALF, eventSize, eventSize)
            }
        } else {
            val totalSpan = (n - 1) * pitch
            var centreX = hostRect.x + hostRect.w / 2.0 - totalSpan / 2.0
            ordered.forEach { be ->
                shapes[be.id] = Rect(centreX - BOUNDARY_HALF, centreY - BOUNDARY_HALF, eventSize, eventSize)
                centreX += pitch
            }
        }
    }
}
