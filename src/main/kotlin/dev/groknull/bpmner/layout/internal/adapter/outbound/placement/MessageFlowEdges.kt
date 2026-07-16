/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.EDGE_LABEL_WIDTH
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.estimateLabelDimensions
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.MessageFlow
import org.camunda.bpm.model.bpmn.instance.Participant

/**
 * Phase-2 processor: derives waypoints for every [MessageFlow] in the model.
 *
 * Postcondition: [PlacementContext.edges] contains a two-point waypoint list for every MessageFlow.
 *
 * Route: a single deterministic straight line whose axis follows the relative position of the two
 * elements (AD-557-11 / AD-557-04 permissible approach (b)):
 *   - Participants stacked vertically (target centre is below source centre): bottom-centre → top-centre.
 *   - Participants arranged horizontally (target centre is to the right of source centre):
 *     right-centre → left-centre.
 *
 * When the source or target is a Participant boundary (no specific flow-node ref resolves),
 * the participant shape's border mid-point is used.
 *
 * Label (if any): placed at the segment midpoint, nudged above the line — the same fixed-box
 * heuristic used for sequence-flow labels.
 *
 * No ELK edges are created for message flows; this processor is the sole owner of their routing.
 */
internal object MessageFlowEdges : PlacementProcessor {

    override fun process(ctx: PlacementContext) {
        val collaboration = ctx.model.getModelElementsByType(Collaboration::class.java).firstOrNull()
            ?: return

        val flows = collaboration.messageFlows.toList()

        // First pass: route all edges so waypoints are available.
        flows.forEach { mf -> routeMessageFlow(mf, ctx) }

        // Second pass: place labels. For vertical flows, all labels share a common label-centre
        // x (average of all vertical edge x values) so they form a vertical column. Horizontal
        // flow labels keep their individual midpoint x.
        val verticalEdgeXValues = flows.mapNotNull { mf ->
            val wps = ctx.edges[mf.id] ?: return@mapNotNull null
            if (isVerticalEdge(wps)) wps[0].x else null
        }
        val sharedLabelCentreX = if (verticalEdgeXValues.isEmpty()) {
            null
        } else {
            verticalEdgeXValues.average()
        }

        flows.forEach { mf -> placeLabel(mf, sharedLabelCentreX, ctx) }
    }

    private fun routeMessageFlow(mf: MessageFlow, ctx: PlacementContext) {
        val srcShape = resolveShape(mf.source, ctx) ?: return
        val tgtShape = resolveShape(mf.target, ctx) ?: return

        val srcCy = srcShape.y + srcShape.h / 2.0
        val tgtCy = tgtShape.y + tgtShape.h / 2.0
        val yCentreDiff = Math.abs(tgtCy - srcCy)
        val xCentreDiff = Math.abs((tgtShape.x + tgtShape.w / 2.0) - (srcShape.x + srcShape.w / 2.0))
        val vertical = yCentreDiff > xCentreDiff

        val srcIsParticipant = mf.source is Participant
        val tgtIsParticipant = mf.target is Participant

        val (srcPt, tgtPt) = if (vertical) {
            val srcCx = srcShape.x + srcShape.w / 2.0
            val tgtCx = tgtShape.x + tgtShape.w / 2.0
            val x = when {
                srcIsParticipant -> tgtCx
                tgtIsParticipant -> srcCx
                else -> srcCx
            }
            if (tgtCy > srcCy) {
                Point(x, srcShape.y + srcShape.h) to Point(x, tgtShape.y)
            } else {
                Point(x, srcShape.y) to Point(x, tgtShape.y + tgtShape.h)
            }
        } else {
            Point(srcShape.x + srcShape.w, srcCy) to Point(tgtShape.x, tgtCy)
        }

        ctx.edges[mf.id] = listOf(srcPt, tgtPt)
    }

    private fun placeLabel(mf: MessageFlow, sharedLabelCentreX: Double?, ctx: PlacementContext) {
        if (mf.name.isNullOrBlank()) return
        val wps = ctx.edges[mf.id]?.takeIf { it.size >= 2 } ?: return
        val (lw, lh) = estimateLabelDimensions(mf.name!!, EDGE_LABEL_WIDTH)
        val midY = (wps[0].y + wps[1].y) / 2.0

        val labelCentreX = if (isVerticalEdge(wps) && sharedLabelCentreX != null) {
            // All vertical message-flow labels share one x centre so they form a column.
            sharedLabelCentreX
        } else {
            (wps[0].x + wps[1].x) / 2.0
        }
        ctx.labels[mf.id] = Rect(labelCentreX - lw / 2.0, midY - lh / 2.0, lw, lh)
    }

    private fun isVerticalEdge(wps: List<Point>): Boolean {
        return wps.size >= 2 && Math.abs(wps[0].x - wps[1].x) < VERTICAL_EPSILON
    }

    private const val VERTICAL_EPSILON = 1.0

    /**
     * Resolves the shape [Rect] for a [org.camunda.bpm.model.bpmn.instance.InteractionNode].
     *
     * An [InteractionNode] may be a [org.camunda.bpm.model.bpmn.instance.FlowNode],
     * a [org.camunda.bpm.model.bpmn.instance.Participant], or (rarely) an
     * [org.camunda.bpm.model.bpmn.instance.EndPoint]. We look up the element's ID in
     * [PlacementContext.shapes] — which by this point contains both flow-node shapes
     * (from [NodeShapeCopy]) and participant shapes (from [CollaborationShapePlacement]).
     */
    private fun resolveShape(
        node: org.camunda.bpm.model.bpmn.instance.InteractionNode?,
        ctx: PlacementContext,
    ): Rect? {
        if (node == null) return null
        return ctx.shapes[(node as? org.camunda.bpm.model.bpmn.instance.BaseElement)?.id ?: return null]
    }
}
