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
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Lane
import org.camunda.bpm.model.bpmn.instance.MessageFlow
import org.camunda.bpm.model.bpmn.instance.Participant
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.SubProcess
import kotlin.math.hypot

/**
 * Projects every collaboration participant from ELK-owned geometry into a single BPMN-declared
 * coordinate frame (AD-622-36): a lane-carrying participant's lanes stack within it; two or more
 * white-box participants stack into one shared column; any black-box participant stacks below
 * the white-box union. Each stacking step is one rigid translation per frame, applied to shapes,
 * waypoints and labels together — never a re-layout of a frame's interior.
 *
 * Cross-participant message flows are excluded from ELK's graph (`BpmnToElkMapper.kt:425`), so
 * they have no ELK-owned route to translate; their route and label are synthesized here instead,
 * once every participant frame has its final position.
 *
 * Single-participant, lane-less collaborations project their one participant's bounds only —
 * their members are already correctly placed by the earlier `NodeShapeCopy` step and asymmetric
 * band padding (AD-622-28), needing no further shift.
 */
@Suppress("TooManyFunctions") // one processor replacing three (P3, AD-622-36): each named phase
// (lane stacking, pool stacking, black-box banding, sequence-flow repair, message-flow synthesis)
// keeps its own small function rather than being crammed into fewer, larger ones.
internal object CollaborationFramePlacement : PlacementProcessor {

    private const val OWNER = "CollaborationFramePlacement"
    private const val LANE_LABEL_WIDTH = PARTICIPANT_HEADER_WIDTH

    /** Vertical spacing between bend heights of message flows sharing the same participant pair. */
    private const val BEND_FAN_GAP = 30.0

    override fun process(ctx: PlacementContext) {
        val collaboration = ctx.model.getModelElementsByType(Collaboration::class.java).firstOrNull() ?: return
        val whiteBox = collaboration.participants.filter { it.process != null }
        val blackBox = collaboration.participants.filter { it.process == null }

        val translations = mutableMapOf<String, Point>()
        whiteBox.forEach { participant -> projectParticipant(participant, ctx, translations) }
        if (whiteBox.size >= 2) stackWhiteBoxPools(whiteBox, ctx, translations)
        blackBox.forEach { participant -> projectBlackBox(participant, ctx) }
        if (blackBox.isNotEmpty() && whiteBox.isNotEmpty()) stackBlackBoxBands(whiteBox, blackBox, ctx)

        retranslateOrRerouteSequenceFlows(translations, ctx)
        synthesizeCrossParticipantMessageFlows(collaboration, ctx)
    }

    // ---- white-box participant + lane projection ----

    private fun projectParticipant(participant: Participant, ctx: PlacementContext, translations: MutableMap<String, Point>) {
        val bounds = ctx.skeleton.nodeMap[participant.id]?.let(::elkBounds) ?: return
        ctx.shapes[participant.id] = bounds
        if (!participant.name.isNullOrBlank()) {
            ctx.labels[participant.id] = Rect(bounds.x, bounds.y, PARTICIPANT_HEADER_WIDTH, bounds.h)
        }
        val lanes = participant.process?.laneSets.orEmpty().flatMap { it.lanes.toList() }
        if (lanes.isNotEmpty()) projectLaneBands(participant, lanes, bounds, ctx, translations)
    }

    private fun projectLaneBands(
        participant: Participant,
        lanes: List<Lane>,
        participantBounds: Rect,
        ctx: PlacementContext,
        translations: MutableMap<String, Point>,
    ) {
        val laneBounds = lanes.mapNotNull { lane -> ctx.skeleton.nodeMap[lane.id]?.let(::elkBounds)?.let { lane to it } }
        if (laneBounds.isEmpty()) return

        var nextY = participantBounds.y
        val laneVectors = mutableMapOf<String, Point>()
        laneBounds.forEach { (lane, elkLaneBounds) ->
            val band = Rect(
                participantBounds.x + PARTICIPANT_HEADER_WIDTH,
                nextY,
                participantBounds.w - PARTICIPANT_HEADER_WIDTH,
                elkLaneBounds.h,
            )
            ctx.shapes[lane.id] = band
            PlacementTranslations.ledgerMove(lane.id, OWNER, ctx)
            if (!lane.name.isNullOrBlank()) {
                ctx.labels[lane.id] = Rect(band.x, band.y, LANE_LABEL_WIDTH, band.h)
            }
            val vector = Point(0.0, band.y - elkLaneBounds.y)
            flowNodeMembers(lane.flowNodeRefs).forEach { memberId -> laneVectors[memberId] = vector }
            nextY += band.h
        }

        val projected = participantBounds.copy(h = nextY - participantBounds.y)
        ctx.shapes[participant.id] = projected
        if (!participant.name.isNullOrBlank()) {
            ctx.labels[participant.id] = Rect(projected.x, projected.y, PARTICIPANT_HEADER_WIDTH, projected.h)
        }

        // A boundary event's own lane translation follows its host — lane flowNodeRefs rarely
        // list it explicitly, so it is not covered by the loop above.
        val boundaryVectors = ctx.model.getModelElementsByType(BoundaryEvent::class.java)
            .mapNotNull { event -> laneVectors[event.attachedTo?.id]?.let { event.id to it } }
            .toMap()

        PlacementTranslations.translateAndLedger(laneVectors + boundaryVectors, OWNER, ctx)
        translations.putAll(laneVectors + boundaryVectors)
    }

    /** Every flow-node in [seeds], recursing into subprocess descendants. */
    private fun flowNodeMembers(seeds: Collection<FlowNode>): Set<String> {
        val members = mutableSetOf<String>()
        fun add(node: FlowNode) {
            members.add(node.id)
            if (node is SubProcess) node.flowElements.filterIsInstance<FlowNode>().forEach(::add)
        }
        seeds.forEach(::add)
        return members
    }

    // ---- ≥2 white-box participants: shared-column stacking ----

    private fun stackWhiteBoxPools(whiteBox: List<Participant>, ctx: PlacementContext, translations: MutableMap<String, Point>) {
        val originalBounds = whiteBox.associateWith { ctx.shapes.getValue(it.id) }
        val bandX = originalBounds.values.minOf { it.x }
        // Each pool's own natural width, not the union of pre-stacking x-ranges: ELK's root
        // Direction.RIGHT layout spreads participants far apart horizontally before this pass
        // translates them into a column, so unioning their x-ranges would inflate every band to
        // the combined side-by-side span instead of the widest pool's actual content width.
        val bandW = originalBounds.values.maxOf { it.w }

        var nextY = originalBounds.getValue(whiteBox.first()).y
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
            // Compose with any lane vector already recorded for this participant's members.
            participantMembers(participant).forEach { id ->
                translations[id] = translations[id]?.let { Point(it.x + vector.x, it.y + vector.y) } ?: vector
            }
            nextY += shape.h + PARTICIPANT_GAP
        }
    }

    /** Every flow-node this participant owns: top-level flow elements plus subprocess descendants and lanes. */
    private fun participantMembers(participant: Participant): Set<String> {
        val members = flowNodeMembers(participant.process?.flowElements?.filterIsInstance<FlowNode>().orEmpty()).toMutableSet()
        participant.process?.laneSets.orEmpty().flatMap { it.lanes }.forEach { members.add(it.id) }
        return members
    }

    // ---- black-box participants: band below the white-box union ----

    private fun projectBlackBox(participant: Participant, ctx: PlacementContext) {
        val bounds = ctx.skeleton.nodeMap[participant.id]?.let(::elkBounds) ?: return
        ctx.shapes[participant.id] = bounds
        if (!participant.name.isNullOrBlank()) {
            ctx.labels[participant.id] = Rect(bounds.x, bounds.y, PARTICIPANT_HEADER_WIDTH, bounds.h)
        }
    }

    private fun stackBlackBoxBands(whiteBox: List<Participant>, blackBox: List<Participant>, ctx: PlacementContext) {
        val whiteBounds = whiteBox.mapNotNull { ctx.shapes[it.id] }
        if (whiteBounds.isEmpty()) return
        val left = whiteBounds.minOf { it.x }
        val width = whiteBounds.maxOf { it.x + it.w } - left
        var nextY = whiteBounds.maxOf { it.y + it.h } + PARTICIPANT_GAP
        blackBox.forEach { participant ->
            val previous = ctx.shapes[participant.id] ?: return@forEach
            val band = Rect(left, nextY, width, previous.h)
            ctx.shapes[participant.id] = band
            ctx.labels[participant.id] = Rect(band.x, band.y, PARTICIPANT_HEADER_WIDTH, band.h)
            PlacementTranslations.ledgerMove(participant.id, OWNER, ctx)
            nextY += band.h + PARTICIPANT_GAP
        }
    }

    // ---- sequence flows: translate whole, or re-route across a frame boundary ----

    private fun retranslateOrRerouteSequenceFlows(translations: Map<String, Point>, ctx: PlacementContext) {
        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { flow -> flow.source?.id in translations || flow.target?.id in translations }
            .sortedBy { it.id }
            .forEach { flow ->
                val sourceVector = translations[flow.source?.id]
                val targetVector = translations[flow.target?.id]
                when {
                    sourceVector != null && sourceVector == targetVector -> translateFlow(flow, sourceVector, ctx)
                    flow.source is BoundaryEvent -> routeException(flow, ctx)
                    else -> routeSequence(flow, ctx)
                }
            }
    }

    /** Shifts an already-ELK-routed flow's waypoints and label by the same rigid vector as its endpoints. */
    private fun translateFlow(flow: SequenceFlow, vector: Point, ctx: PlacementContext) {
        ctx.edges[flow.id] = ctx.edges[flow.id]?.map { Point(it.x + vector.x, it.y + vector.y) } ?: return
        ctx.labels[flow.id]?.let { label -> ctx.labels[flow.id] = label.copy(x = label.x + vector.x, y = label.y + vector.y) }
    }

    /**
     * Re-anchors an exception edge after its lane/participant band shifted independently of its
     * handler: a deterministic drop-then-horizontal route from the boundary's new position to the
     * handler's new position, mirroring [routeSequence]'s own simple case.
     */
    private fun routeException(flow: SequenceFlow, ctx: PlacementContext) {
        val boundary = flow.source as? BoundaryEvent ?: return
        val source = ctx.shapes[boundary.id] ?: return
        val target = ctx.shapes[flow.target?.id] ?: return
        val startX = source.x + source.w / 2.0
        val startY = source.y + source.h
        val targetCy = target.y + target.h / 2.0
        val enterX = if (target.x >= startX) target.x else target.x + target.w
        ctx.edges[flow.id] = listOf(Point(startX, startY), Point(startX, targetCy), Point(enterX, targetCy))
        centerLabelOnRoute(flow.id, flow.name, ctx)
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
            centerLabelOnRoute(flow.id, flow.name, ctx)
            return
        }

        val sourceAboveTarget = sourceMiddleY < targetMiddleY
        val start = Point(source.x + source.w / 2.0, if (sourceAboveTarget) source.y + source.h else source.y)
        val end = Point(target.x + target.w / 2.0, if (sourceAboveTarget) target.y else target.y + target.h)
        val bendY = (start.y + end.y) / 2.0
        ctx.edges[flow.id] = listOf(start, Point(start.x, bendY), Point(end.x, bendY), end)
        centerLabelOnRoute(flow.id, flow.name, ctx)
    }

    // ---- message flows: never an ELK graph edge, always synthesized ----

    /**
     * Every message flow whose endpoints sit in different participants. A black-box participant
     * has no interior width to fan against, so a flow touching one is a direct line at the
     * *other*, white-box endpoint's own centre (clamped into the black-box band); a flow between
     * two white-box participants fans both endpoints' exit/entry points across their own width
     * when several flows share one, bending at most once when the fanned positions don't align.
     */
    private fun synthesizeCrossParticipantMessageFlows(collaboration: Collaboration, ctx: PlacementContext) {
        val participantOf = mutableMapOf<String, String>()
        val blackBoxIds = mutableSetOf<String>()
        collaboration.participants.forEach { participant ->
            participantOf[participant.id] = participant.id
            if (participant.process != null) {
                participantMembers(participant).forEach { participantOf[it] = participant.id }
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
        centerLabelOnRoute(flow.id, flow.name, ctx)
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
        centerLabelOnRoute(flow.id, flow.name, ctx)
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

    /** Centres [id]'s label on its current [PlacementContext.edges] route, if it has one and a name. */
    private fun centerLabelOnRoute(id: String, name: String?, ctx: PlacementContext) {
        if (name.isNullOrBlank()) return
        val route = ctx.edges[id] ?: return
        val (width, height) = BpmnPlacementPass.estimateLabelDimensions(name, BpmnPlacementPass.EDGE_LABEL_WIDTH)
        val mid = pathMidpoint(route)
        ctx.labels[id] = Rect(mid.x - width / 2.0, mid.y - height / 2.0, width, height)
    }

    /**
     * The point halfway along [route] by cumulative segment length (not the midpoint of its first
     * and last waypoints, which for a route with an unequal bend on each side can land off the
     * polyline entirely).
     */
    private fun pathMidpoint(route: List<Point>): Point {
        if (route.size <= 1) return route.first()
        val segments = route.zipWithNext()
        val lengths = segments.map { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
        val total = lengths.sum()
        if (total <= 0.0) return route.first()
        var remaining = total / 2.0
        segments.forEachIndexed { i, (a, b) ->
            val len = lengths[i]
            if (remaining <= len) {
                val t = if (len > 0.0) remaining / len else 0.0
                return Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
            remaining -= len
        }
        return route.last()
    }

    private fun elkBounds(node: org.eclipse.elk.graph.ElkNode): Rect {
        val (x, y) = BpmnPlacementPass.absolutePosition(node)
        return Rect(x, y, node.width, node.height)
    }
}
