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
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.MessageFlow
import org.camunda.bpm.model.bpmn.instance.Participant
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.SubProcess

/**
 * Stacks two-or-more white-box collaboration participants into a shared full-width column of
 * pool bands (ELK's root `Direction.RIGHT` layout packs them left-to-right instead). Translates
 * each participant's own subtree only — never re-lays out its interior — and repairs only the
 * flows the translation moves: same-participant flows shift whole, cross-pool message flows are
 * re-routed between the two bands' facing edges.
 *
 * Single-participant collaborations early-return: lane/pool projection there stays
 * [CollaborationShapePlacement]'s job.
 */
internal object WhiteBoxPoolBandPlacement : PlacementProcessor {

    private const val OWNER = "WhiteBoxPoolBandPlacement"

    override fun process(ctx: PlacementContext) {
        val collaboration = ctx.model.getModelElementsByType(Collaboration::class.java).firstOrNull() ?: return
        val whiteBox = collaboration.participants.filter { it.process != null }
        if (whiteBox.size < 2) return

        val originalBounds = whiteBox.associateWith { ctx.shapes[it.id] ?: return }
        val bandX = originalBounds.values.minOf { it.x }
        val bandW = originalBounds.values.maxOf { it.x + it.w } - bandX

        var nextY = originalBounds.getValue(whiteBox.first()).y
        val translations = mutableMapOf<String, Point>()
        whiteBox.forEach { participant ->
            val shape = originalBounds.getValue(participant)
            val vector = Point(bandX - shape.x, nextY - shape.y)
            ctx.shapes[participant.id] = Rect(bandX, nextY, bandW, shape.h)
            PlacementTranslations.ledgerMove(participant.id, OWNER, ctx)
            if (!participant.name.isNullOrBlank()) {
                ctx.labels[participant.id] = Rect(bandX, nextY, PARTICIPANT_HEADER_WIDTH, shape.h)
            }
            PlacementTranslations.translateAndLedger(
                participantMembers(participant).associateWith { vector },
                OWNER,
                ctx,
            )
            translations[participant.id] = vector
            nextY += shape.h + PARTICIPANT_GAP
        }

        val memberOfParticipant = whiteBox.flatMap { p -> participantMembers(p).map { it to p.id } }.toMap()
        repairSequenceFlows(memberOfParticipant, translations, ctx)
        repairCrossPoolMessageFlows(collaboration.messageFlows, memberOfParticipant, ctx)
    }

    /** Every flow-node this participant owns: top-level flow elements plus subprocess descendants and lanes. */
    private fun participantMembers(participant: Participant): Set<String> {
        val members = mutableSetOf<String>()
        fun add(node: FlowNode) {
            members.add(node.id)
            if (node is SubProcess) {
                node.flowElements.filterIsInstance<FlowNode>().forEach(::add)
            }
        }
        participant.process?.flowElements?.filterIsInstance<FlowNode>()?.forEach(::add)
        participant.process?.laneSets.orEmpty().flatMap { it.lanes }.forEach { members.add(it.id) }
        return members
    }

    private fun repairSequenceFlows(
        memberOfParticipant: Map<String, String>,
        translations: Map<String, Point>,
        ctx: PlacementContext,
    ) {
        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { flow ->
                val sourceParticipant = memberOfParticipant[flow.source?.id]
                sourceParticipant != null && sourceParticipant == memberOfParticipant[flow.target?.id]
            }
            .sortedBy { it.id }
            .forEach { flow ->
                val participantId = memberOfParticipant[flow.source?.id] ?: return@forEach
                val vector = translations.getValue(participantId)
                if (flow.id in ctx.skeleton.loopBackFlowIds) {
                    LoopBackEdgeArcs.routeAndStore(flow, ctx)
                } else {
                    ctx.edges[flow.id] = ctx.edges[flow.id]?.map { point ->
                        Point(point.x + vector.x, point.y + vector.y)
                    } ?: return@forEach
                }
            }
    }

    private fun repairCrossPoolMessageFlows(
        flows: Collection<MessageFlow>,
        memberOfParticipant: Map<String, String>,
        ctx: PlacementContext,
    ) {
        flows.filter { flow ->
            val sourceParticipant = memberOfParticipant[flow.source?.id]
            val targetParticipant = memberOfParticipant[flow.target?.id]
            sourceParticipant != null && targetParticipant != null && sourceParticipant != targetParticipant
        }
            .sortedBy { it.id }
            .forEach { flow -> routeCrossPoolMessage(flow, ctx) }
    }

    /**
     * Routes one cross-pool message flow between its endpoints' final (post-stacking) shapes,
     * exiting the source's boundary facing the target pool and entering the target's boundary
     * facing the source pool — at most one lateral bend when the endpoints' centres don't align.
     */
    private fun routeCrossPoolMessage(flow: MessageFlow, ctx: PlacementContext) {
        val source = ctx.shapes[flow.source?.id] ?: return
        val target = ctx.shapes[flow.target?.id] ?: return
        val sourceCx = source.x + source.w / 2.0
        val targetCx = target.x + target.w / 2.0
        val sourceAboveTarget = source.y < target.y
        val start = Point(sourceCx, if (sourceAboveTarget) source.y + source.h else source.y)
        val end = Point(targetCx, if (sourceAboveTarget) target.y else target.y + target.h)
        val route = if (sourceCx == targetCx) {
            listOf(start, end)
        } else {
            val bendY = (start.y + end.y) / 2.0
            listOf(start, Point(start.x, bendY), Point(end.x, bendY), end)
        }
        ctx.edges[flow.id] = route
        if (!flow.name.isNullOrBlank()) {
            val (width, height) = BpmnPlacementPass.estimateLabelDimensions(flow.name, BpmnPlacementPass.EDGE_LABEL_WIDTH)
            val midX = (start.x + end.x) / 2.0
            val midY = (start.y + end.y) / 2.0
            ctx.labels[flow.id] = Rect(midX - width / 2.0, midY - height / 2.0, width, height)
        }
    }
}
