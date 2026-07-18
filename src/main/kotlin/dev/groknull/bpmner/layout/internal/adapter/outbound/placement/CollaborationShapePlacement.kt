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

    /** Minimum gap between a lane/participant's left-side name label and a node's own label. */
    private const val LABEL_CLEARANCE_MARGIN = 16.0

    /** Minimum gap between a loop-back arc's peak and the top edge of its enclosing lane. */
    private const val LANE_ARC_TOP_MARGIN = 8.0

    /** Minimum straight length of a loop-back arc's final segment, before it enters its target. */
    private const val MIN_ARC_TAIL = 16.0

    /** Waypoint count of the 4-point arc [LoopBackEdgeArcs.routeLoopBackEdge] always produces. */
    private const val LOOP_BACK_ARC_WAYPOINT_COUNT = 4

    /**
     * Extra headroom reserved above a lane's normally-centred content when the lane contains a
     * loop-back flow's target, so [LANE_ARC_TOP_MARGIN] and [MIN_ARC_TAIL] both fit without
     * competing for the same standard [LANE_PADDING] gap above the tallest member node.
     */
    private const val LOOP_BACK_LANE_TOP_RESERVE = 16.0

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
        val totalLaneHeight = laneHeights.sumOf { it.height }

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
        refreshLoopBackEdgeRoutes(ctx, allNodeIds)

        return Rect(participantX, participantY, participantW, totalLaneHeight)
    }

    /** A lane's canonical height plus any extra headroom reserved at its top. */
    private data class LaneHeightInfo(val height: Double, val topReserve: Double)

    /**
     * Each lane's canonical height: tallest member node + 2×LANE_PADDING, minimum MIN_LANE_HEIGHT,
     * plus [LOOP_BACK_LANE_TOP_RESERVE] extra height when the lane contains a loop-back flow's
     * target — see [routeLoopBackEdgeWithinLane].
     */
    private fun laneCanonicalHeights(lanes: List<Lane>, ctx: PlacementContext): List<LaneHeightInfo> {
        val loopBackTargets = loopBackTargetIds(ctx)
        return lanes.map { lane ->
            val maxNodeH = lane.flowNodeRefs.mapNotNull { ctx.shapes[it.id] }.maxOfOrNull { it.h } ?: 0.0
            val topReserve = if (lane.flowNodeRefs.any { it.id in loopBackTargets }) {
                LOOP_BACK_LANE_TOP_RESERVE
            } else {
                0.0
            }
            LaneHeightInfo(maxOf(MIN_LANE_HEIGHT, maxNodeH + 2.0 * LANE_PADDING) + topReserve, topReserve)
        }
    }

    /** The target-node IDs of every loop-back sequence flow in the model. */
    private fun loopBackTargetIds(ctx: PlacementContext): Set<String> {
        return ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.id in ctx.skeleton.loopBackFlowIds }
            .mapNotNull { it.target?.id }
            .toSet()
    }

    /**
     * Returns the X shift needed so the leftmost node's centred label (width [LABEL_WIDTH])
     * clears [laneX] by [LABEL_CLEARANCE_MARGIN], leaving a visible gap to the lane/participant
     * name label instead of merely avoiding overlap. Zero if no nudge is needed.
     */
    private fun labelClearanceDx(nodeShapes: List<Rect>, laneX: Double): Double {
        val leftmost = nodeShapes.minByOrNull { it.x } ?: return 0.0
        val nodeCentreX = leftmost.x + leftmost.w / 2.0
        return maxOf(0.0, laneX + LABEL_CLEARANCE_MARGIN + LABEL_WIDTH / 2.0 - nodeCentreX)
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
     * When [LaneHeightInfo.topReserve] is nonzero, member nodes are centred within the band
     * *below* the reserved strip, not the full lane height, leaving that headroom empty at the
     * lane's top for [routeLoopBackEdgeWithinLane]'s arc.
     *
     * When a lane member is an expanded [SubProcess], its internal child nodes (already placed
     * at absolute coordinates by earlier pipeline phases) are shifted by the same Y delta so
     * they stay attached to their container, mirroring the recursive descendant handling
     * [shiftNodesX] applies on the X axis.
     *
     * A [BoundaryEvent] lane member is never independently centred: [BoundaryShapePlacement]
     * already positioned it relative to its host task (bottom edge minus half the event's
     * height), so it is instead shifted by its host task's own centring delta, preserving that
     * relative offset. Deferred to a second pass so the host's delta is known regardless of
     * flowNodeRefs order or which lane the host is in.
     */
    private fun stackLanes(
        lanes: List<Lane>,
        laneHeights: List<LaneHeightInfo>,
        geo: LaneGeometry,
        ctx: PlacementContext,
    ) {
        var laneY = geo.startY
        val dyByNodeId = mutableMapOf<String, Double>()
        val boundaryRefs = mutableListOf<BoundaryEvent>()
        for ((i, lane) in lanes.withIndex()) {
            val info = laneHeights[i]
            val laneH = info.height
            ctx.shapes[lane.id] = Rect(geo.laneX, laneY, geo.laneW, laneH)
            if (!lane.name.isNullOrBlank()) {
                ctx.labels[lane.id] = Rect(geo.participantX, laneY, PARTICIPANT_HEADER_WIDTH, laneH)
            }
            boundaryRefs.addAll(centerLaneMembers(lane, laneY, info, ctx, dyByNodeId))
            laneY += laneH
        }
        shiftBoundaryEventsWithHost(boundaryRefs, dyByNodeId, ctx)
    }

    /**
     * Centres every non-[BoundaryEvent] member of [lane] within its Y-band, recording each
     * member's centring delta in [dyByNodeId] and returning the lane's [BoundaryEvent] members
     * unshifted, for [shiftBoundaryEventsWithHost] to reposition afterwards.
     */
    private fun centerLaneMembers(
        lane: Lane,
        laneY: Double,
        info: LaneHeightInfo,
        ctx: PlacementContext,
        dyByNodeId: MutableMap<String, Double>,
    ): List<BoundaryEvent> {
        val contentY = laneY + info.topReserve
        val contentH = info.height - info.topReserve
        val members = lane.flowNodeRefs.toList()
        members.filterNot { it is BoundaryEvent }.forEach { nodeRef ->
            val nodeShape = ctx.shapes[nodeRef.id] ?: return@forEach
            val newY = contentY + (contentH - nodeShape.h) / 2.0
            val dy = newY - nodeShape.y
            ctx.shapes[nodeRef.id] = nodeShape.copy(y = newY)
            dyByNodeId[nodeRef.id] = dy
            if (dy != 0.0 && nodeRef is SubProcess) shiftSubProcessDescendants(nodeRef, dy, ctx)
        }
        return members.filterIsInstance<BoundaryEvent>()
    }

    /**
     * Shifts each [BoundaryEvent] in [boundaryRefs] by its host task's centring delta (looked up
     * in [dyByNodeId]), preserving the host-relative offset [BoundaryShapePlacement] established
     * instead of independently re-centring the event in its lane band.
     */
    private fun shiftBoundaryEventsWithHost(
        boundaryRefs: List<BoundaryEvent>,
        dyByNodeId: Map<String, Double>,
        ctx: PlacementContext,
    ) {
        boundaryRefs.forEach { be ->
            val hostId = be.attachedTo?.id ?: return@forEach
            val dy = dyByNodeId[hostId]?.takeIf { it != 0.0 } ?: return@forEach
            val shape = ctx.shapes[be.id] ?: return@forEach
            ctx.shapes[be.id] = shape.copy(y = shape.y + dy)
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
        return hasRepositionedEndpoint(sf, repositionedIds)
    }

    /** True when either endpoint of [sf] is in [repositionedIds]. */
    private fun hasRepositionedEndpoint(sf: SequenceFlow, repositionedIds: Set<String>): Boolean {
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

    /**
     * Re-routes loop-back arcs whose source or target shape was repositioned by lane stacking
     * or the label-clearance shift.
     *
     * [LoopBackEdgeArcs] computes these arcs before lane placement runs, so they go stale once
     * [stackLanes]/[shiftNodesX] move an endpoint's shape — the same staleness
     * [refreshExceptionEdgeRoutes] fixes for boundary-event arcs. [isRefreshableSequenceFlow]
     * deliberately excludes loop-back flows from [refreshSequenceEdgeWaypoints] to avoid
     * overwriting the arc with a straight line; this recomputes the arc itself from the final
     * shape positions instead, reusing [LoopBackEdgeArcs]'s routing logic.
     */
    private fun refreshLoopBackEdgeRoutes(ctx: PlacementContext, repositionedIds: Set<String>) {
        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { sf -> sf.id in ctx.skeleton.loopBackFlowIds && hasRepositionedEndpoint(sf, repositionedIds) }
            .forEach { sf -> routeLoopBackEdgeWithinLane(sf, ctx) }
    }

    /**
     * Re-routes [sf] via [LoopBackEdgeArcs], then clamps the arc's peak (the shared Y of its two
     * middle waypoints) to satisfy two constraints at once:
     *
     * - it must not cross above the top edge of the lane containing its endpoints
     *   ([LANE_ARC_TOP_MARGIN] below the lane's top), and
     * - its final segment into the target must have a visible straight length before the
     *   arrowhead, not a 90° turn immediately at the target's edge ([MIN_ARC_TAIL]).
     *
     * [LoopBackEdgeArcs]'s fallback clearance (used when there is no enclosing SubProcess) only
     * clears the topmost endpoint by a fixed distance — it has no notion of a lane boundary or a
     * minimum tail, so for endpoints near a lane's top band it can push the arc's peak above the
     * lane itself, or leave the arc's peak so close to the target that the turn and the
     * arrowhead visually collide. [LOOP_BACK_LANE_TOP_RESERVE] guarantees these two constraints
     * never conflict.
     */
    private fun routeLoopBackEdgeWithinLane(sf: SequenceFlow, ctx: PlacementContext) {
        LoopBackEdgeArcs.routeAndStore(sf, ctx)
        val inputs = loopBackClampInputs(sf, ctx) ?: return
        val waypoints = inputs.waypoints
        val minY = inputs.laneTop + LANE_ARC_TOP_MARGIN
        val maxY = maxOf(minY, inputs.tgtTop - MIN_ARC_TAIL)
        val peakY = waypoints[1].y.coerceIn(minY, maxY)
        if (peakY == waypoints[1].y) return
        ctx.edges[sf.id] = listOf(waypoints[0], waypoints[1].copy(y = peakY), waypoints[2].copy(y = peakY), waypoints.last())
    }

    /** The values [routeLoopBackEdgeWithinLane] needs to clamp a re-routed arc, or null if any is missing. */
    private data class LoopBackClampInputs(val waypoints: List<Point>, val laneTop: Double, val tgtTop: Double)

    private fun loopBackClampInputs(sf: SequenceFlow, ctx: PlacementContext): LoopBackClampInputs? {
        val waypoints = ctx.edges[sf.id]?.takeIf { it.size == LOOP_BACK_ARC_WAYPOINT_COUNT } ?: return null
        val laneTop = enclosingLaneTop(sf, ctx) ?: return null
        val tgtTop = sf.target?.id?.let { ctx.shapes[it]?.y } ?: return null
        return LoopBackClampInputs(waypoints, laneTop, tgtTop)
    }

    /** Returns the top Y of the [Lane] containing [sf]'s source node, if any. */
    private fun enclosingLaneTop(sf: SequenceFlow, ctx: PlacementContext): Double? {
        val sourceId = sf.source?.id ?: return null
        val lane = ctx.model.getModelElementsByType(Lane::class.java)
            .firstOrNull { lane -> lane.flowNodeRefs.any { it.id == sourceId } } ?: return null
        return ctx.shapes[lane.id]?.y
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
