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

/** Projects external black-box participants below one in-scope white-box participant. */
internal object ExternalBlackBoxBandPlacement : PlacementProcessor {

    private const val OWNER = "ExternalBlackBoxBandPlacement"

    override fun process(ctx: PlacementContext) {
        val collaboration = ctx.model.getModelElementsByType(Collaboration::class.java).firstOrNull() ?: return
        val whiteBox = collaboration.participants.filter { it.process != null }.singleOrNull() ?: return
        val whiteBounds = ctx.shapes[whiteBox.id] ?: return
        var nextY = whiteBounds.y + whiteBounds.h + PARTICIPANT_GAP
        collaboration.participants.filter { it.process == null }.forEach { participant ->
            val previous = ctx.shapes[participant.id] ?: return@forEach
            val band = Rect(whiteBounds.x, nextY, whiteBounds.w, previous.h)
            ctx.shapes[participant.id] = band
            ctx.labels[participant.id] = Rect(band.x, band.y, PARTICIPANT_HEADER_WIDTH, band.h)
            recordMove(participant, band, ctx)
            reattachTerminals(participant, band, collaboration.messageFlows, ctx)
            nextY += band.h + PARTICIPANT_GAP
        }
    }

    private fun recordMove(participant: Participant, band: Rect, ctx: PlacementContext) {
        val node = ctx.skeleton.nodeMap[participant.id] ?: return
        val (x, y) = BpmnPlacementPass.absolutePosition(node)
        ctx.moves[participant.id] = MoveRecord(OWNER, band.x - x, band.y - y)
    }

    private fun reattachTerminals(
        participant: Participant,
        band: Rect,
        flows: Collection<MessageFlow>,
        ctx: PlacementContext,
    ) {
        flows.filter { it.source?.id == participant.id || it.target?.id == participant.id }.forEach { flow ->
            val points = ctx.edges[flow.id] ?: return@forEach
            if (points.size < 2) return@forEach
            ctx.edges[flow.id] = if (flow.source?.id == participant.id) {
                listOf(intersection(points[1], band)) + points.drop(1)
            } else {
                points.dropLast(1) + intersection(points[points.lastIndex - 1], band)
            }
        }
    }

    private fun intersection(adjacent: Point, rect: Rect): Point {
        val center = Point(rect.x + rect.w / 2.0, rect.y + rect.h / 2.0)
        val dx = center.x - adjacent.x
        val dy = center.y - adjacent.y
        val candidates = listOf(rect.x, rect.x + rect.w)
            .mapNotNull { x -> if (dx == 0.0) null else (x - adjacent.x) / dx }
            .plus(listOf(rect.y, rect.y + rect.h).mapNotNull { y -> if (dy == 0.0) null else (y - adjacent.y) / dy })
            .filter { it in 0.0..1.0 }
            .filter { t ->
                val x = adjacent.x + dx * t
                val y = adjacent.y + dy * t
                x in rect.x..rect.x + rect.w && y in rect.y..rect.y + rect.h
            }
        val t = candidates.minOrNull() ?: return center
        return Point(adjacent.x + dx * t, adjacent.y + dy * t)
    }
}
