/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.BLACK_BOX_HEIGHT
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.BLACK_BOX_WIDTH
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.PARTICIPANT_GAP
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.PARTICIPANT_HEADER_WIDTH
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.Lane
import org.camunda.bpm.model.bpmn.instance.Participant

/** Projects ELK-owned participant and lane compound bounds into BPMN-DI shapes. */
internal object CollaborationShapePlacement : PlacementProcessor {

    private const val DEFAULT_PARTICIPANT_Y = 12.0

    override fun process(ctx: PlacementContext) {
        val collaboration = ctx.model.getModelElementsByType(Collaboration::class.java).firstOrNull() ?: return
        val whiteBox = collaboration.participants.filter { it.process != null }
        whiteBox.forEach { participant -> copyWhiteBoxBounds(participant, ctx) }
        copyBlackBoxBounds(collaboration.participants.filter { it.process == null }, whiteBox, ctx)
    }

    private fun copyWhiteBoxBounds(participant: Participant, ctx: PlacementContext) {
        val bounds = ctx.skeleton.nodeMap[participant.id]?.let(::elkBounds) ?: return
        ctx.shapes[participant.id] = bounds
        if (!participant.name.isNullOrBlank()) {
            ctx.labels[participant.id] = Rect(bounds.x, bounds.y, PARTICIPANT_HEADER_WIDTH, bounds.h)
        }
        participant.process?.laneSets.orEmpty().flatMap { it.lanes.toList() }.forEach { lane ->
            copyLaneBounds(lane, bounds, ctx)
        }
    }

    private fun copyLaneBounds(lane: Lane, participantBounds: Rect, ctx: PlacementContext) {
        val bounds = ctx.skeleton.nodeMap[lane.id]?.let(::elkBounds) ?: return
        ctx.shapes[lane.id] = bounds
        if (!lane.name.isNullOrBlank()) {
            ctx.labels[lane.id] = Rect(participantBounds.x, bounds.y, PARTICIPANT_HEADER_WIDTH, bounds.h)
        }
    }

    private fun copyBlackBoxBounds(blackBox: List<Participant>, whiteBox: List<Participant>, ctx: PlacementContext) {
        val whiteBounds = whiteBox.mapNotNull { ctx.shapes[it.id] }
        val left = whiteBounds.minOfOrNull { it.x } ?: 0.0
        val width = whiteBounds.maxOfOrNull { it.x + it.w }?.minus(left) ?: BLACK_BOX_WIDTH
        var y = whiteBounds.maxOfOrNull { it.y + it.h }?.plus(PARTICIPANT_GAP) ?: DEFAULT_PARTICIPANT_Y
        blackBox.forEach { participant ->
            val bounds = Rect(left, y, width, BLACK_BOX_HEIGHT)
            ctx.shapes[participant.id] = bounds
            if (!participant.name.isNullOrBlank()) {
                ctx.labels[participant.id] = Rect(bounds.x, bounds.y, PARTICIPANT_HEADER_WIDTH, bounds.h)
            }
            y += BLACK_BOX_HEIGHT + PARTICIPANT_GAP
        }
    }

    private fun elkBounds(node: org.eclipse.elk.graph.ElkNode): Rect {
        val (x, y) = BpmnPlacementPass.absolutePosition(node)
        return Rect(x, y, node.width, node.height)
    }
}
