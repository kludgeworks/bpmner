/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.PARTICIPANT_GAP
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.PARTICIPANT_HEADER_WIDTH
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.MessageFlow
import org.camunda.bpm.model.bpmn.instance.Participant

/** Projects external black-box participants below the in-scope white-box participant(s). */
internal object ExternalBlackBoxBandPlacement : PlacementProcessor {

    private const val OWNER = "ExternalBlackBoxBandPlacement"

    override fun process(ctx: PlacementContext) {
        val collaboration = ctx.model.getModelElementsByType(Collaboration::class.java).firstOrNull() ?: return
        val whiteBoxes = collaboration.participants.filter { it.process != null }
        val whiteBounds = whiteBoxes.mapNotNull { ctx.shapes[it.id] }
        if (whiteBounds.isEmpty()) return
        // One or more modeled pools may already be stacked into a shared band by
        // WhiteBoxPoolBandPlacement (which runs earlier in the pipeline); span their union so a
        // black-box band always sits below every modeled pool, not just the first one found.
        val left = whiteBounds.minOf { it.x }
        val width = whiteBounds.maxOf { it.x + it.w } - left
        var nextY = whiteBounds.maxOf { it.y + it.h } + PARTICIPANT_GAP
        collaboration.participants.filter { it.process == null }.forEach { participant ->
            val previous = ctx.shapes[participant.id] ?: return@forEach
            val band = Rect(left, nextY, width, previous.h)
            ctx.shapes[participant.id] = band
            ctx.labels[participant.id] = Rect(band.x, band.y, PARTICIPANT_HEADER_WIDTH, band.h)
            recordMove(participant, band, ctx)
            regenerateRoutes(participant, band, collaboration.messageFlows, ctx)
            nextY += band.h + PARTICIPANT_GAP
        }
    }

    private fun recordMove(participant: Participant, band: Rect, ctx: PlacementContext) {
        val node = ctx.skeleton.nodeMap[participant.id] ?: return
        val (x, y) = BpmnPlacementPass.absolutePosition(node)
        ctx.moves[participant.id] = MoveRecord(OWNER, band.x - x, band.y - y)
    }

    /**
     * Builds each incident message flow as a direct route to the relocated band. These flows are
     * never modelled as ELK graph edges ([BpmnToElkMapper.mapMessageFlows] excludes them, since
     * ELK cannot know this BPMN presentation exception's final position), so this is the sole
     * owner of their route and label, not a repair of an ELK-produced one. The band is always
     * below the white-box participant(s), so the fixed endpoint's bottom edge always faces the
     * band's top edge: the route is a single vertical run between them, with the label centred
     * on its midpoint.
     */
    private fun regenerateRoutes(
        participant: Participant,
        band: Rect,
        flows: Collection<MessageFlow>,
        ctx: PlacementContext,
    ) {
        flows.filter { it.source?.id == participant.id || it.target?.id == participant.id }.forEach { flow ->
            val fromBlackBox = flow.source?.id == participant.id
            val fixedId = if (fromBlackBox) flow.target?.id else flow.source?.id
            val fixed = ctx.shapes[fixedId] ?: return@forEach
            val cx = (fixed.x + fixed.w / 2.0).coerceIn(band.x, band.x + band.w)
            val fixedPoint = Point(cx, fixed.y + fixed.h)
            val bandPoint = Point(cx, band.y)
            val route = if (fromBlackBox) listOf(bandPoint, fixedPoint) else listOf(fixedPoint, bandPoint)
            ctx.edges[flow.id] = route
            EdgeLabelReposition.reposition(flow.id, flow.name, ctx)
        }
    }
}
