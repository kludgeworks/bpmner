/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.SequenceFlow

/**
 * Boundary event shapes on host bottom edge.
 *
 * Every boundary-event shape is placed on its host's BOTTOM edge, straddling
 * (centre on the edge), evenly distributed for multiple attachments.
 *
 * Minimum gap between adjacent boundary shapes on the same host edge: [BOUNDARY_MIN_GAP].
 *
 * Handler alignment: after boundaries are positioned left-to-right by handler y, the handler
 * y-positions are re-sorted to match boundary x-order (leftmost boundary → topmost handler).
 * This eliminates edge crossings that arise when ELK sequences handlers in declaration order
 * rather than in the order dictated by boundary x-position.
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
            placeBoundariesOnHost(hostRect, boundaries, ctx, handlerOf)
        }
    }

    private fun placeBoundariesOnHost(
        hostRect: Rect,
        boundaries: List<BoundaryEvent>,
        ctx: PlacementContext,
        handlerOf: Map<String, String>,
    ) {
        val shapes = ctx.shapes
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

        alignHandlerYsByBoundaryXOrder(ordered, handlerOf, ctx)
    }

    /**
     * Re-sorts handler Y-positions to match the left-to-right boundary order.
     *
     * The leftmost boundary's vertical drop passes behind the rightmost boundary's
     * horizontal jog, so the leftmost handler must sit deepest (highest Y) and the
     * rightmost handler shallowest. Assigning Y values in descending order to
     * boundaries in left-to-right order eliminates edge crossings.
     *
     * Also updates the move ledger so [HandlerComponentAlignment.Repair] uses the
     * final post-swap Y when re-routing edges.
     */
    private fun alignHandlerYsByBoundaryXOrder(
        ordered: List<BoundaryEvent>,
        handlerOf: Map<String, String>,
        ctx: PlacementContext,
    ) {
        val shapes = ctx.shapes
        val handlerIds = ordered.mapNotNull { handlerOf[it.id] }
        val handlerYs = handlerIds.mapNotNull { shapes[it]?.y }.sortedDescending()
        handlerIds.zip(handlerYs).forEach { (hid, targetY) ->
            val s = shapes[hid] ?: return@forEach
            if (s.y != targetY) {
                shapes[hid] = s.copy(y = targetY)
                val prior = ctx.moves[hid]
                if (prior != null && prior.owner == "HandlerComponentAlignment") {
                    ctx.moves[hid] = prior.copy(dy = prior.dy + (targetY - s.y))
                }
            }
        }
    }
}
