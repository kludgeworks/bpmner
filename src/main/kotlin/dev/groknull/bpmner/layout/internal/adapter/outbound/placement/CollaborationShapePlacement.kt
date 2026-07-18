/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.LABEL_WIDTH
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.BLACK_BOX_HEIGHT
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.BLACK_BOX_WIDTH
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.PARTICIPANT_GAP
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.PARTICIPANT_HEADER_WIDTH
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.Lane
import org.camunda.bpm.model.bpmn.instance.Participant
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.SubProcess

/**
 * Phase-2 processor: derives [Rect] bounds for every [Participant] shape and every [Lane] shape,
 * and repositions flow nodes into their swim-lane Y-bands.
 *
 * Postcondition: [PlacementContext.shapes] contains a [Rect] for every Participant and Lane.
 * For laned participants, each flow node's Y is centered within its lane's band, and every
 * sequence flow whose endpoints were repositioned has its waypoints refreshed.
 *
 * For white-box participants the horizontal (X/width) bounds come from the union of already-placed
 * ELK node X positions. Lane heights are computed from member node sizes (not positions), stacked
 * vertically in document order so every lane is tall enough to contain its tallest node. A fixed
 * header band is added on the left side (PARTICIPANT_HEADER_WIDTH).
 *
 * For black-box participants the width matches the white-box participants and a fixed height
 * ([BLACK_BOX_HEIGHT]) is used. They are stacked below all white-box participants with [PARTICIPANT_GAP].
 */
// process(), the white-box/lane/unlaned bounds computations, and the node-shift/edge-refresh
// helpers all cooperate on one shared PlacementContext for a single participant/lane placement
// pass; splitting into separate objects would require threading ctx and lane geometry through
// each, or grouping them under a shared context that adds indirection without reducing the
// function count. Suppression is structural, not incidental.
@Suppress("TooManyFunctions")
internal object CollaborationShapePlacement : PlacementProcessor {

    private const val LANE_PADDING = 20.0
    private const val PARTICIPANT_PADDING = 20.0
    private const val MIN_LANE_HEIGHT = 120.0
    private const val DEFAULT_PARTICIPANT_Y = 12.0
    private const val SAME_LANE_Y_EPSILON = 1.0

    override fun process(ctx: PlacementContext) {
        val collaboration = ctx.model.getModelElementsByType(Collaboration::class.java).firstOrNull()
            ?: return

        val participants = collaboration.participants.toList()
        val whiteBox = participants.filter { it.process != null }
        val blackBox = participants.filter { it.process == null }

        var stackY = DEFAULT_PARTICIPANT_Y
        var whiteBoxLeft = 0.0
        var whiteBoxRight = BLACK_BOX_WIDTH

        for (participant in whiteBox) {
            val bounds = computeWhiteBoxBounds(participant, ctx, startY = stackY)
            ctx.shapes[participant.id] = bounds
            if (!participant.name.isNullOrBlank()) {
                ctx.labels[participant.id] = Rect(bounds.x, bounds.y, PARTICIPANT_HEADER_WIDTH, bounds.h)
            }
            stackY += bounds.h + PARTICIPANT_GAP
            if (whiteBox.first() === participant) {
                whiteBoxLeft = bounds.x
                whiteBoxRight = bounds.x + bounds.w
            } else {
                whiteBoxLeft = minOf(whiteBoxLeft, bounds.x)
                whiteBoxRight = maxOf(whiteBoxRight, bounds.x + bounds.w)
            }
        }
        val whiteBoxWidth = whiteBoxRight - whiteBoxLeft

        var bbY = stackY
        for (participant in blackBox) {
            val bounds = Rect(whiteBoxLeft, bbY, whiteBoxWidth, BLACK_BOX_HEIGHT)
            ctx.shapes[participant.id] = bounds
            bbY += BLACK_BOX_HEIGHT + PARTICIPANT_GAP
            if (!participant.name.isNullOrBlank()) {
                ctx.labels[participant.id] = Rect(bounds.x, bounds.y, PARTICIPANT_HEADER_WIDTH, bounds.h)
            }
        }
    }

    /**
     * Computes the bounding box for a white-box participant.
     *
     * When the participant's process has lanes, lanes are stacked vertically with heights derived
     * from the tallest member node (not from ELK Y positions). Nodes are then repositioned to be
     * vertically centred within their lane's band, and sequence flow waypoints are refreshed.
     *
     * When there are no lanes, bounds are derived from the union of all placed node shapes plus
     * uniform padding and a header band on the left.
     */
    private fun computeWhiteBoxBounds(participant: Participant, ctx: PlacementContext, startY: Double): Rect {
        val process = participant.process ?: return Rect(0.0, startY, BLACK_BOX_WIDTH, BLACK_BOX_HEIGHT)
        val lanes = mutableListOf<Lane>()
        process.laneSets.forEach { ls -> lanes.addAll(ls.lanes) }

        return if (lanes.isNotEmpty()) {
            computeLanedParticipantBounds(participant, lanes, ctx, startY)
        } else {
            computeUnlanedParticipantBounds(participant, ctx, startY)
        }
    }

    /**
     * Computes bounds for a participant with swim-lanes.
     *
     * Lane heights are derived from node sizes (not ELK Y positions). Lanes stack vertically in
     * document order. Nodes are repositioned into their lane Y-band and nudged right when the
     * leftmost node label would otherwise overlap the lane header band. Sequence flow waypoints
     * are refreshed with orthogonal routing so cross-lane edges use right-angle bends.
     */
    private fun computeLanedParticipantBounds(
        participant: Participant,
        lanes: List<Lane>,
        ctx: PlacementContext,
        startY: Double,
    ): Rect {
        val process = participant.process ?: return Rect(0.0, startY, BLACK_BOX_WIDTH, BLACK_BOX_HEIGHT)
        val allNodeIds = collectAllFlowNodeIds(process)

        val nodeShapes = allNodeIds.mapNotNull { ctx.shapes[it] }
        val minX = if (nodeShapes.isEmpty()) 0.0 else nodeShapes.minOf { it.x }
        val maxX = if (nodeShapes.isEmpty()) BLACK_BOX_WIDTH else nodeShapes.maxOf { it.x + it.w }

        val laneHeights = laneCanonicalHeights(lanes, ctx)
        val totalLaneHeight = laneHeights.sum()

        val contentLeft = minX - PARTICIPANT_PADDING
        val participantX = contentLeft - PARTICIPANT_HEADER_WIDTH
        val participantY = startY
        val laneX = participantX + PARTICIPANT_HEADER_WIDTH

        val dx = labelClearanceDx(nodeShapes, laneX)
        if (dx > 0.0) shiftNodesX(allNodeIds, dx, ctx)

        val participantW = (maxX + dx + PARTICIPANT_PADDING) - participantX
        val laneW = participantW - PARTICIPANT_HEADER_WIDTH

        stackLanes(lanes, laneHeights, LaneGeometry(laneX, laneW, participantX, participantY), ctx)
        refreshSequenceEdgeWaypoints(ctx, allNodeIds)
        refreshExceptionEdgeRoutes(ctx, allNodeIds)

        return Rect(participantX, participantY, participantW, totalLaneHeight)
    }

    /** Each lane's canonical height: tallest member node + 2×LANE_PADDING, minimum MIN_LANE_HEIGHT. */
    private fun laneCanonicalHeights(lanes: List<Lane>, ctx: PlacementContext): List<Double> {
        return lanes.map { lane ->
            val maxNodeH = lane.flowNodeRefs.mapNotNull { ctx.shapes[it.id] }.maxOfOrNull { it.h } ?: 0.0
            maxOf(MIN_LANE_HEIGHT, maxNodeH + 2.0 * LANE_PADDING)
        }
    }

    /**
     * Returns the X shift needed so the leftmost node's centred label (width [LABEL_WIDTH])
     * does not intrude left of [laneX]. Zero if no nudge is needed.
     */
    private fun labelClearanceDx(nodeShapes: List<Rect>, laneX: Double): Double {
        val leftmost = nodeShapes.minByOrNull { it.x } ?: return 0.0
        val nodeCentreX = leftmost.x + leftmost.w / 2.0
        return maxOf(0.0, laneX + LABEL_WIDTH / 2.0 - nodeCentreX)
    }

    private fun shiftNodesX(ids: Set<String>, dx: Double, ctx: PlacementContext) {
        for (id in ids) {
            val s = ctx.shapes[id] ?: continue
            ctx.shapes[id] = s.copy(x = s.x + dx)
        }
    }

    /** Geometry constants for writing lane and node shapes within one participant. */
    private data class LaneGeometry(
        val laneX: Double,
        val laneW: Double,
        val participantX: Double,
        val startY: Double,
    )

    /**
     * Assigns lane shapes, lane labels, and centres member nodes vertically within each band.
     *
     * When a lane member is an expanded [SubProcess], its internal child nodes (already placed
     * at absolute coordinates by earlier pipeline phases) are shifted by the same Y delta so
     * they stay attached to their container, mirroring the recursive descendant handling
     * [shiftNodesX] applies on the X axis.
     */
    private fun stackLanes(
        lanes: List<Lane>,
        laneHeights: List<Double>,
        geo: LaneGeometry,
        ctx: PlacementContext,
    ) {
        var laneY = geo.startY
        for ((i, lane) in lanes.withIndex()) {
            val laneH = laneHeights[i]
            ctx.shapes[lane.id] = Rect(geo.laneX, laneY, geo.laneW, laneH)
            if (!lane.name.isNullOrBlank()) {
                ctx.labels[lane.id] = Rect(geo.participantX, laneY, PARTICIPANT_HEADER_WIDTH, laneH)
            }
            for (nodeRef in lane.flowNodeRefs) {
                val nodeShape = ctx.shapes[nodeRef.id] ?: continue
                val newY = laneY + (laneH - nodeShape.h) / 2.0
                val dy = newY - nodeShape.y
                ctx.shapes[nodeRef.id] = nodeShape.copy(y = newY)
                if (dy != 0.0 && nodeRef is SubProcess) shiftSubProcessDescendants(nodeRef, dy, ctx)
            }
            laneY += laneH
        }
    }

    /**
     * Shifts a subprocess lane member's internal child nodes and their sequence-flow waypoints
     * by [dy] so they stay attached to the container after [stackLanes] repositions it.
     */
    private fun shiftSubProcessDescendants(sub: SubProcess, dy: Double, ctx: PlacementContext) {
        val nodeIds = mutableSetOf<String>()
        val flowIds = mutableSetOf<String>()
        collectFlowElementIds(sub.flowElements.toList(), nodeIds, nodesOnly = true)
        collectFlowElementIds(sub.flowElements.toList(), flowIds, nodesOnly = false)
        shiftNodesY(nodeIds, flowIds, dy, ctx)
    }

    /**
     * Computes bounds for a participant with no lanes.
     *
     * Shifts nodes (and their internal sequence-flow edge waypoints) so the topmost node
     * sits [PARTICIPANT_PADDING] below [startY], then returns the bounding box of all placed
     * nodes with uniform padding and a left header band.
     */
    private fun computeUnlanedParticipantBounds(
        participant: Participant,
        ctx: PlacementContext,
        startY: Double,
    ): Rect {
        val process = participant.process ?: return Rect(0.0, startY, BLACK_BOX_WIDTH, BLACK_BOX_HEIGHT)
        val nodeIds = collectAllFlowNodeIds(process)
        val nodeShapes = nodeIds.mapNotNull { ctx.shapes[it] }

        if (nodeShapes.isEmpty()) return Rect(0.0, startY, BLACK_BOX_WIDTH * 2, BLACK_BOX_HEIGHT * 2)

        val elkMinY = nodeShapes.minOf { it.y }
        val dy = (startY + PARTICIPANT_PADDING) - elkMinY
        if (dy != 0.0) {
            shiftNodesY(nodeIds, collectAllFlowIds(process), dy, ctx)
        }

        val shifted = nodeIds.mapNotNull { ctx.shapes[it] }
        val minX = shifted.minOf { it.x }
        val minY = shifted.minOf { it.y }
        val maxX = shifted.maxOf { it.x + it.w }
        val maxY = shifted.maxOf { it.y + it.h }

        val participantX = minX - PARTICIPANT_PADDING - PARTICIPANT_HEADER_WIDTH
        val participantY = minY - PARTICIPANT_PADDING
        val participantW = maxX + PARTICIPANT_PADDING - participantX
        val participantH = maxY + PARTICIPANT_PADDING - participantY

        return Rect(participantX, participantY, participantW, participantH)
    }

    /**
     * Shifts all node shapes in [nodeIds] and all edge waypoints in [flowIds] by [dy] along
     * the Y axis. Edge waypoints must move with their nodes so edges stay attached after the shift.
     *
     * Parallel to [shiftNodesX], which only moves shapes; Y-shifting must also move edges because
     * ELK-produced waypoints are tied to the absolute node positions.
     */
    private fun shiftNodesY(nodeIds: Set<String>, flowIds: Set<String>, dy: Double, ctx: PlacementContext) {
        for (id in nodeIds) {
            val s = ctx.shapes[id] ?: continue
            ctx.shapes[id] = s.copy(y = s.y + dy)
        }
        for (flowId in flowIds) {
            val wps = ctx.edges[flowId] ?: continue
            ctx.edges[flowId] = wps.map { it.copy(y = it.y + dy) }
        }
    }

    /**
     * Refreshes sequence flow waypoints after lane repositioning.
     *
     * For same-lane edges (source and target at the same Y-centre): two waypoints,
     * straight horizontal.
     *
     * For cross-lane edges (source and target in different Y-bands): four waypoints,
     * orthogonal L-shape — exit source right-centre → horizontal to midX → vertical to
     * target Y-centre → enter target left-centre. This avoids diagonal lines across
     * lane boundaries.
     *
     * Only flows whose source or target is in [repositionedIds] are updated.
     */
    private fun refreshSequenceEdgeWaypoints(
        ctx: PlacementContext,
        repositionedIds: Set<String>,
    ) {
        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { sf -> isRefreshableSequenceFlow(sf, repositionedIds, ctx) }
            .forEach { sf ->
                val srcShape = ctx.shapes[sf.source?.id ?: return@forEach] ?: return@forEach
                val tgtShape = ctx.shapes[sf.target?.id ?: return@forEach] ?: return@forEach
                val srcRight = srcShape.x + srcShape.w
                val srcMidY = srcShape.y + srcShape.h / 2.0
                val tgtLeft = tgtShape.x
                val tgtMidY = tgtShape.y + tgtShape.h / 2.0
                ctx.edges[sf.id] = if (kotlin.math.abs(srcMidY - tgtMidY) < SAME_LANE_Y_EPSILON) {
                    // Same lane: straight horizontal connection.
                    listOf(Point(srcRight, srcMidY), Point(tgtLeft, tgtMidY))
                } else {
                    // Cross-lane: orthogonal L-shape with a bend at the midpoint X.
                    val midX = (srcRight + tgtLeft) / 2.0
                    listOf(
                        Point(srcRight, srcMidY),
                        Point(midX, srcMidY),
                        Point(midX, tgtMidY),
                        Point(tgtLeft, tgtMidY),
                    )
                }
            }
    }

    /**
     * Returns true when [sf]'s waypoints should be refreshed by lane repositioning.
     *
     * Excluded: loop-back flows (already arc-routed by [LoopBackEdgeArcs]) and flows
     * whose source is a BoundaryEvent (already arc-routed by [ExceptionEdgeRoutes]).
     * In both cases, refreshing would overwrite the arc with a straight line.
     *
     * Only flows with at least one endpoint in [repositionedIds] need refreshing.
     */
    private fun isRefreshableSequenceFlow(
        sf: SequenceFlow,
        repositionedIds: Set<String>,
        ctx: PlacementContext,
    ): Boolean {
        if (sf.id in ctx.skeleton.loopBackFlowIds) return false
        if (sf.source is BoundaryEvent) return false
        val srcId = sf.source?.id
        val tgtId = sf.target?.id
        return (srcId != null && srcId in repositionedIds) || (tgtId != null && tgtId in repositionedIds)
    }

    /**
     * Re-routes boundary-event exception-flow arcs whose source shape was repositioned
     * by lane stacking or the label-clearance shift.
     *
     * [ExceptionEdgeRoutes] computes these arcs before lane placement runs, so they go
     * stale once [stackLanes]/[shiftNodesX] move the boundary event's shape.
     * [isRefreshableSequenceFlow] deliberately excludes boundary-event flows from
     * [refreshSequenceEdgeWaypoints] to avoid overwriting the arc with a straight line;
     * this recomputes the arc itself from the final shape positions instead, using the
     * same routing logic.
     */
    private fun refreshExceptionEdgeRoutes(ctx: PlacementContext, repositionedIds: Set<String>) {
        val boundaryEvents = ctx.model.getModelElementsByType(BoundaryEvent::class.java)
            .associateBy { it.id }
        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { sf -> sf.source?.id in boundaryEvents && sf.source?.id in repositionedIds }
            .forEach { sf ->
                val be = boundaryEvents[sf.source?.id] ?: return@forEach
                val bRect = ctx.shapes[be.id] ?: return@forEach
                val tRect = ctx.shapes[sf.target?.id ?: return@forEach] ?: return@forEach
                val hostRect = ctx.shapes[be.attachedTo?.id]
                ctx.edges[sf.id] = ExceptionEdgeRoutes.routeExceptionEdge(bRect, tRect, hostRect)
            }
    }
}

/** Recursively collects all flow-node IDs from a process, including subprocess children. */
internal fun collectAllFlowNodeIds(process: org.camunda.bpm.model.bpmn.instance.Process): Set<String> {
    val ids = mutableSetOf<String>()
    collectFlowElementIds(process.flowElements.toList(), ids, nodesOnly = true)
    return ids
}

/** Recursively collects sequence-flow IDs from a process, including subprocess children. */
internal fun collectAllFlowIds(process: org.camunda.bpm.model.bpmn.instance.Process): Set<String> {
    val ids = mutableSetOf<String>()
    collectFlowElementIds(process.flowElements.toList(), ids, nodesOnly = false)
    return ids
}

/**
 * Recursively collects IDs from [elements], descending into subprocesses.
 *
 * [nodesOnly] = true collects flow-node IDs; false collects sequence-flow IDs.
 */
private fun collectFlowElementIds(
    elements: List<org.camunda.bpm.model.bpmn.instance.FlowElement>,
    ids: MutableSet<String>,
    nodesOnly: Boolean,
) {
    for (el in elements) {
        when (el) {
            is org.camunda.bpm.model.bpmn.instance.FlowNode -> {
                if (nodesOnly) ids.add(el.id)
                if (el is org.camunda.bpm.model.bpmn.instance.SubProcess) {
                    collectFlowElementIds(el.flowElements.toList(), ids, nodesOnly)
                }
            }
            is org.camunda.bpm.model.bpmn.instance.SequenceFlow -> {
                if (!nodesOnly) ids.add(el.id)
            }
            else -> Unit
        }
    }
}
