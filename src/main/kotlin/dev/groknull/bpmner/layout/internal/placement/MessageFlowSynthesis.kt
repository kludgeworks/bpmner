/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.MessageFlow

/**
 * Synthesizes the route and label for every cross-participant message flow, since these are
 * excluded from ELK's graph and so have no ELK-owned geometry to translate.
 *
 * A black-box participant has no interior width to fan against, so a flow touching one is a
 * direct line at the *other*, white-box endpoint's own centre, clamped into the black-box band.
 * A flow between two white-box participants fans both endpoints' exit/entry points across their
 * own width when several flows share one, bending at most once when the fanned positions don't
 * align.
 */
internal object MessageFlowSynthesis {

    /** Vertical spacing between bend heights of message flows sharing the same participant pair. */
    private const val BEND_FAN_GAP = 30.0

    fun synthesize(collaboration: Collaboration, ctx: PlacementContext) {
        val participantOf = mutableMapOf<String, String>()
        val blackBoxIds = mutableSetOf<String>()
        collaboration.participants.forEach { participant ->
            participantOf[participant.id] = participant.id
            if (participant.process != null) {
                CollaborationFramePlacement.participantMembers(participant).forEach { participantOf[it] = participant.id }
            } else {
                blackBoxIds.add(participant.id)
            }
        }

        val crossFlows = collaboration.messageFlows.filter { flow ->
            val source = participantOf[flow.source?.id]
            val target = participantOf[flow.target?.id]
            source != null && target != null && source != target
        }.sortedBy { it.id }

        // Multiple same-category flows may share one endpoint; fan their exit/entry points
        // across its width instead of stacking them all on the same centre-x corridor.
        val incidentFlowIds = mutableMapOf<String, MutableList<String>>()
        crossFlows.forEach { flow ->
            flow.source?.id?.let { incidentFlowIds.getOrPut(it) { mutableListOf() }.add(flow.id) }
            flow.target?.id?.let { incidentFlowIds.getOrPut(it) { mutableListOf() }.add(flow.id) }
        }

        // Multiple flows between the same pair of white-box participants would otherwise bend
        // at the same height (both ends sit in the same two row-bands); fan the bend height too,
        // the vertical analogue of fanCx, so their labels don't land on top of each other.
        val whiteWhitePairOf = crossFlows.filter { it.source?.id !in blackBoxIds && it.target?.id !in blackBoxIds }
            .associateBy({ it.id }) { setOf(participantOf[it.source?.id], participantOf[it.target?.id]) }
        val pairSiblings = whiteWhitePairOf.entries.groupBy({ it.value }) { it.key }

        crossFlows.forEach { flow ->
            val sourceId = flow.source?.id
            val targetId = flow.target?.id
            if (sourceId in blackBoxIds || targetId in blackBoxIds) {
                routeBlackBoxMessage(flow, sourceId in blackBoxIds, ctx)
            } else {
                val siblings = pairSiblings.getValue(whiteWhitePairOf.getValue(flow.id))
                routeWhiteToWhiteMessage(flow, incidentFlowIds, fanBendOffset(flow.id, siblings), ctx)
            }
        }
    }

    /** Vertical offset for [flowId]'s bend height, spread evenly around zero among [siblings]. */
    private fun fanBendOffset(flowId: String, siblings: List<String>): Double {
        if (siblings.size <= 1) return 0.0
        val index = siblings.sorted().indexOf(flowId)
        return (index - (siblings.size - 1) / 2.0) * BEND_FAN_GAP
    }

    /**
     * A black-box participant is a single full-width band, not a set of distinct member shapes,
     * so there is nothing to fan against: the route is a single vertical run at the *fixed*
     * (white-box) endpoint's own centre, clamped into the band so it still lands on it.
     */
    private fun routeBlackBoxMessage(flow: MessageFlow, fromBlackBox: Boolean, ctx: PlacementContext) {
        val sourceId = flow.source?.id ?: return
        val targetId = flow.target?.id ?: return
        val source = ctx.shapes[sourceId] ?: return
        val target = ctx.shapes[targetId] ?: return
        val fixed = if (fromBlackBox) target else source
        val band = if (fromBlackBox) source else target
        val cx = (fixed.x + fixed.w / 2.0).coerceIn(band.x, band.x + band.w)
        val fixedPoint = Point(cx, if (fromBlackBox) target.y + target.h else source.y + source.h)
        val bandPoint = Point(cx, if (fromBlackBox) source.y else target.y)
        ctx.edges[flow.id] = if (fromBlackBox) listOf(bandPoint, fixedPoint) else listOf(fixedPoint, bandPoint)
        CollaborationFramePlacement.centerLabelOnRoute(flow.id, flow.name, ctx)
    }

    private fun routeWhiteToWhiteMessage(
        flow: MessageFlow,
        incidentFlowIds: Map<String, List<String>>,
        bendOffset: Double,
        ctx: PlacementContext,
    ) {
        val sourceId = flow.source?.id ?: return
        val targetId = flow.target?.id ?: return
        val source = ctx.shapes[sourceId] ?: return
        val target = ctx.shapes[targetId] ?: return
        val sourceCx = fanCx(source, sourceId, flow.id, incidentFlowIds)
        val targetCx = fanCx(target, targetId, flow.id, incidentFlowIds)
        val sourceAboveTarget = source.y < target.y
        val start = Point(sourceCx, if (sourceAboveTarget) source.y + source.h else source.y)
        val end = Point(targetCx, if (sourceAboveTarget) target.y else target.y + target.h)
        val route = if (sourceCx == targetCx) {
            listOf(start, end)
        } else {
            val bendY = (start.y + end.y) / 2.0 + bendOffset
            listOf(start, Point(start.x, bendY), Point(end.x, bendY), end)
        }
        ctx.edges[flow.id] = route
        CollaborationFramePlacement.centerLabelOnRoute(flow.id, flow.name, ctx)
    }

    /**
     * Exit/entry x for [flowId] at [nodeId]: the node's centre if it's the sole incident
     * cross-participant flow, otherwise fanned evenly across the node's width so sibling flows
     * sharing the same endpoint don't draw on top of each other.
     */
    private fun fanCx(shape: Rect, nodeId: String, flowId: String, incidentFlowIds: Map<String, List<String>>): Double {
        val siblings = incidentFlowIds[nodeId].orEmpty()
        if (siblings.size <= 1) return shape.x + shape.w / 2.0
        val index = siblings.indexOf(flowId)
        return shape.x + shape.w * (index + 1) / (siblings.size + 1)
    }
}
