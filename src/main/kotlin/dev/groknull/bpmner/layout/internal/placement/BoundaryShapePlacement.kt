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
 * Every boundary-event shape is centred on wherever its exception edge ([SequenceEdgeElkCopy])
 * actually starts (AD-622-08), straddling the host's BOTTOM edge there. Anchoring to the edge's
 * own settled start point — rather than recomputing the port's declared geometry independently —
 * keeps the shape and the edge geometrically consistent regardless of how ELK's north/south port
 * handling distributes multiple attachments on one host (`EdgeTerminalTailGuardTest`'s
 * boundary-anchored-terminal invariant). Falls back to the port's own position for a boundary
 * event with no outgoing flow (invalid BPMN, but the mapper still creates its port).
 *
 * Runs after [SequenceEdgeElkCopy] in the pipeline so `ctx.edges` is already populated.
 */
internal object BoundaryShapePlacement : PlacementProcessor {

    private const val BOUNDARY_HALF = BpmnToElkMapper.EVENT_SIZE / 2.0

    override fun process(ctx: PlacementContext) {
        val eventSize = BpmnToElkMapper.EVENT_SIZE
        val outgoingFlowOf = ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.source is BoundaryEvent }
            .associateBy { (it.source as BoundaryEvent).id }

        ctx.model.getModelElementsByType(BoundaryEvent::class.java).forEach { be ->
            val hostId = be.attachedTo?.id ?: return@forEach
            val hostNode = ctx.skeleton.nodeMap[hostId] ?: return@forEach
            val port = ctx.skeleton.portMap[be.id] ?: return@forEach
            // The exception edge leaves from the boundary shape's bottom-centre (matching
            // bpmn-js's rendered connector origin), so the shape's centre-X/bottom-Y is the
            // edge's own start point, not its centre.
            val edgeStart = outgoingFlowOf[be.id]?.let { ctx.edges[it.id]?.firstOrNull() }
            val (centreX, bottomY) = if (edgeStart != null) {
                edgeStart.x to edgeStart.y
            } else {
                val (hostAbsX, hostAbsY) = BpmnPlacementPass.absolutePosition(hostNode)
                (hostAbsX + port.x + port.width / 2.0) to (hostAbsY + hostNode.height + BOUNDARY_HALF)
            }
            ctx.shapes[be.id] = Rect(centreX - BOUNDARY_HALF, bottomY - eventSize, eventSize, eventSize)
        }
    }
}
