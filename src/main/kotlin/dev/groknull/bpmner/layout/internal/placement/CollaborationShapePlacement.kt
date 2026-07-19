/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.BLACK_BOX_HEIGHT
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.BLACK_BOX_WIDTH
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.PARTICIPANT_GAP
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.PARTICIPANT_HEADER_WIDTH
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Lane
import org.camunda.bpm.model.bpmn.instance.Participant
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.SubProcess

/** Projects ELK-owned participant bounds and BPMN's ordered lane bands into BPMN-DI shapes. */
internal object CollaborationShapePlacement : PlacementProcessor {

    private const val DEFAULT_PARTICIPANT_Y = 12.0
    private const val OWNER = "CollaborationShapePlacement"
    private const val LANE_LABEL_WIDTH = PARTICIPANT_HEADER_WIDTH

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
        projectLaneBands(participant, bounds, ctx)
    }

    private fun projectLaneBands(participant: Participant, participantBounds: Rect, ctx: PlacementContext) {
        val lanes = participant.process?.laneSets.orEmpty().flatMap { it.lanes.toList() }
        if (lanes.isEmpty()) return

        val laneBounds = lanes.mapNotNull { lane ->
            ctx.skeleton.nodeMap[lane.id]?.let(::elkBounds)?.let { lane to it }
        }
        if (laneBounds.isEmpty()) return

        var nextY = participantBounds.y
        val translations = mutableMapOf<String, Point>()
        laneBounds.forEach { (lane, elkLaneBounds) ->
            val band = Rect(
                participantBounds.x + PARTICIPANT_HEADER_WIDTH,
                nextY,
                participantBounds.w - PARTICIPANT_HEADER_WIDTH,
                elkLaneBounds.h,
            )
            ctx.shapes[lane.id] = band
            recordTranslation(lane.id, ctx)
            if (!lane.name.isNullOrBlank()) {
                ctx.labels[lane.id] = Rect(
                    band.x,
                    band.y,
                    LANE_LABEL_WIDTH,
                    band.h,
                )
            }
            val translation = Point(
                participantBounds.x + PARTICIPANT_HEADER_WIDTH + LANE_LABEL_WIDTH - elkLaneBounds.x,
                band.y - elkLaneBounds.y,
            )
            laneMembers(lane).forEach { memberId -> translations[memberId] = translation }
            nextY += band.h
        }
        val projectedParticipant = participantBounds.copy(h = nextY - participantBounds.y)
        ctx.shapes[participant.id] = projectedParticipant
        if (!participant.name.isNullOrBlank()) {
            ctx.labels[participant.id] = Rect(
                projectedParticipant.x,
                projectedParticipant.y,
                PARTICIPANT_HEADER_WIDTH,
                projectedParticipant.h,
            )
        }
        repairTranslatedRoutes(translateMembers(translations, ctx), ctx)
    }

    private fun laneMembers(lane: Lane): Set<String> {
        val members = mutableSetOf<String>()
        fun add(node: FlowNode) {
            members.add(node.id)
            if (node is SubProcess) {
                node.flowElements.filterIsInstance<FlowNode>().forEach(::add)
            }
        }
        lane.flowNodeRefs.forEach(::add)
        return members
    }

    private fun translateMembers(translations: Map<String, Point>, ctx: PlacementContext): Map<String, Point> {
        val boundaryTranslations = ctx.model.getModelElementsByType(BoundaryEvent::class.java)
            .mapNotNull { event -> translations[event.attachedTo?.id]?.let { event.id to it } }
            .toMap()
        val allTranslations = translations + boundaryTranslations
        allTranslations.forEach { (id, translation) ->
            val rect = ctx.shapes[id] ?: return@forEach
            ctx.shapes[id] = rect.copy(x = rect.x + translation.x, y = rect.y + translation.y)
            recordTranslation(id, ctx)
        }
        return allTranslations
    }

    private fun recordTranslation(id: String, ctx: PlacementContext) {
        val elkNode = ctx.skeleton.nodeMap[id] ?: return
        val placed = ctx.shapes[id] ?: return
        val (x, y) = BpmnPlacementPass.absolutePosition(elkNode)
        ctx.moves[id] = MoveRecord(OWNER, placed.x - x, placed.y - y)
    }

    private fun repairTranslatedRoutes(translations: Map<String, Point>, ctx: PlacementContext) {
        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter {
                it.source?.id in translations || it.target?.id in translations
            }
            .sortedBy { it.id }
            .forEach { flow ->
                val sourceTranslation = translations[flow.source?.id]
                val targetTranslation = translations[flow.target?.id]
                when {
                    flow.id in ctx.skeleton.loopBackFlowIds -> LoopBackEdgeArcs.routeAndStore(flow, ctx)
                    sourceTranslation != null && sourceTranslation == targetTranslation -> {
                        ctx.edges[flow.id] = ctx.edges[flow.id]?.map { point ->
                            Point(point.x + sourceTranslation.x, point.y + sourceTranslation.y)
                        } ?: return@forEach
                    }
                    flow.source is BoundaryEvent -> routeException(flow, ctx)
                    else -> routeSequence(flow, ctx)
                }
            }
    }

    private fun routeException(flow: SequenceFlow, ctx: PlacementContext) {
        val boundary = flow.source as? BoundaryEvent ?: return
        val source = ctx.shapes[boundary.id] ?: return
        val target = ctx.shapes[flow.target?.id] ?: return
        ctx.edges[flow.id] = ExceptionEdgeRoutes.routeExceptionEdge(source, target, ctx.shapes[boundary.attachedTo?.id])
    }

    private fun routeSequence(flow: SequenceFlow, ctx: PlacementContext) {
        val source = ctx.shapes[flow.source?.id] ?: return
        val target = ctx.shapes[flow.target?.id] ?: return
        val sourceMiddleY = source.y + source.h / 2.0
        val targetMiddleY = target.y + target.h / 2.0
        if (sourceMiddleY == targetMiddleY) {
            ctx.edges[flow.id] = listOf(
                Point(source.x + source.w, sourceMiddleY),
                Point(target.x, targetMiddleY),
            )
            return
        }

        val sourceAboveTarget = sourceMiddleY < targetMiddleY
        val start = Point(source.x + source.w / 2.0, if (sourceAboveTarget) source.y + source.h else source.y)
        val end = Point(target.x + target.w / 2.0, if (sourceAboveTarget) target.y else target.y + target.h)
        val bendY = (start.y + end.y) / 2.0
        ctx.edges[flow.id] = listOf(start, Point(start.x, bendY), Point(end.x, bendY), end)
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
