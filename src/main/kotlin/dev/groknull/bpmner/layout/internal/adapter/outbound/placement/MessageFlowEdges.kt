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

        collaboration.messageFlows.forEach { mf -> routeMessageFlow(mf, ctx) }
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
            // Straight vertical line: use the flow-node's x-centre for both endpoints so the
            // line is perpendicular to the participant border rather than diagonal.
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
            Point(srcShape.x + srcShape.w, srcCy) to
                Point(tgtShape.x, tgtCy)
        }

        ctx.edges[mf.id] = listOf(srcPt, tgtPt)

        if (!mf.name.isNullOrBlank()) {
            val midX = (srcPt.x + tgtPt.x) / 2.0
            val midY = (srcPt.y + tgtPt.y) / 2.0
            val (lw, lh) = estimateLabelDimensions(mf.name!!, EDGE_LABEL_WIDTH)
            // Centre the label vertically on the edge mid-point so that labels on parallel
            // vertical edges (same midY) share the same top Y regardless of text height.
            ctx.labels[mf.id] = Rect(midX - lw / 2.0, midY - lh / 2.0, lw, lh)
        }
    }

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
