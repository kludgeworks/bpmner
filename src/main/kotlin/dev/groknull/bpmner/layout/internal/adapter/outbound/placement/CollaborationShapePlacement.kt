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
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.Lane
import org.camunda.bpm.model.bpmn.instance.Participant
import org.camunda.bpm.model.bpmn.instance.SequenceFlow

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

        var maxWhiteBoxBottom = 0.0
        var whiteBoxLeft = 0.0
        var whiteBoxWidth = BLACK_BOX_WIDTH

        for (participant in whiteBox) {
            val bounds = computeWhiteBoxBounds(participant, ctx)
            ctx.shapes[participant.id] = bounds
            maxWhiteBoxBottom = maxOf(maxWhiteBoxBottom, bounds.y + bounds.h)
            whiteBoxLeft = bounds.x
            whiteBoxWidth = bounds.w

            if (!participant.name.isNullOrBlank()) {
                ctx.labels[participant.id] = Rect(bounds.x, bounds.y, PARTICIPANT_HEADER_WIDTH, bounds.h)
            }
        }

        var bbY = maxWhiteBoxBottom + PARTICIPANT_GAP
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
    private fun computeWhiteBoxBounds(participant: Participant, ctx: PlacementContext): Rect {
        val process = participant.process ?: return Rect(0.0, 0.0, BLACK_BOX_WIDTH, BLACK_BOX_HEIGHT)
        val lanes = mutableListOf<Lane>()
        process.laneSets.forEach { ls -> lanes.addAll(ls.lanes) }

        return if (lanes.isNotEmpty()) {
            computeLanedParticipantBounds(participant, lanes, ctx)
        } else {
            computeUnlanedParticipantBounds(participant, ctx)
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
    ): Rect {
        val process = participant.process ?: return Rect(0.0, 0.0, BLACK_BOX_WIDTH, BLACK_BOX_HEIGHT)
        val allNodeIds = collectAllFlowNodeIds(process)

        val nodeShapes = allNodeIds.mapNotNull { ctx.shapes[it] }
        val minX = if (nodeShapes.isEmpty()) 0.0 else nodeShapes.minOf { it.x }
        val maxX = if (nodeShapes.isEmpty()) BLACK_BOX_WIDTH else nodeShapes.maxOf { it.x + it.w }

        val laneHeights = laneCanonicalHeights(lanes, ctx)
        val totalLaneHeight = laneHeights.sum()

        val contentLeft = minX - PARTICIPANT_PADDING
        val participantX = contentLeft - PARTICIPANT_HEADER_WIDTH
        val participantY = if (nodeShapes.isEmpty()) DEFAULT_PARTICIPANT_Y else nodeShapes.minOf { it.y } - PARTICIPANT_PADDING
        val laneX = participantX + PARTICIPANT_HEADER_WIDTH

        val dx = labelClearanceDx(nodeShapes, laneX)
        if (dx > 0.0) shiftNodesX(allNodeIds, dx, ctx)

        val participantW = (maxX + dx + PARTICIPANT_PADDING) - participantX
        val laneW = participantW - PARTICIPANT_HEADER_WIDTH

        stackLanes(lanes, laneHeights, LaneGeometry(laneX, laneW, participantX, participantY), ctx)
        refreshSequenceEdgeWaypoints(ctx, allNodeIds)

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

    /** Assigns lane shapes, lane labels, and centres member nodes vertically within each band. */
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
                ctx.shapes[nodeRef.id] = nodeShape.copy(y = laneY + (laneH - nodeShape.h) / 2.0)
            }
            laneY += laneH
        }
    }

    /**
     * Computes bounds for a participant with no lanes.
     *
     * Bounds are the union of all placed node shapes plus uniform padding and a left header band.
     */
    private fun computeUnlanedParticipantBounds(participant: Participant, ctx: PlacementContext): Rect {
        val process = participant.process ?: return Rect(0.0, 0.0, BLACK_BOX_WIDTH, BLACK_BOX_HEIGHT)
        val nodeIds = collectAllFlowNodeIds(process)
        val nodeShapes = nodeIds.mapNotNull { ctx.shapes[it] }

        if (nodeShapes.isEmpty()) return Rect(0.0, 0.0, BLACK_BOX_WIDTH * 2, BLACK_BOX_HEIGHT * 2)

        val minX = nodeShapes.minOf { it.x }
        val minY = nodeShapes.minOf { it.y }
        val maxX = nodeShapes.maxOf { it.x + it.w }
        val maxY = nodeShapes.maxOf { it.y + it.h }

        val contentX = minX - PARTICIPANT_PADDING
        val contentY = minY - PARTICIPANT_PADDING
        val contentRight = maxX + PARTICIPANT_PADDING
        val contentBottom = maxY + PARTICIPANT_PADDING

        val participantX = contentX - PARTICIPANT_HEADER_WIDTH
        val participantY = contentY
        val participantW = contentRight - participantX
        val participantH = contentBottom - contentY

        return Rect(participantX, participantY, participantW, participantH)
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
            .filter { sf ->
                val srcId = sf.source?.id
                val tgtId = sf.target?.id
                (srcId != null && srcId in repositionedIds) || (tgtId != null && tgtId in repositionedIds)
            }
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

    /** Recursively collects all flow-node IDs from a process, including subprocess children. */
    private fun collectAllFlowNodeIds(process: org.camunda.bpm.model.bpmn.instance.Process): Set<String> {
        val ids = mutableSetOf<String>()
        collectFromElements(process.flowElements.toList(), ids)
        return ids
    }

    private fun collectFromElements(
        elements: List<org.camunda.bpm.model.bpmn.instance.FlowElement>,
        ids: MutableSet<String>,
    ) {
        for (el in elements) {
            when (el) {
                is org.camunda.bpm.model.bpmn.instance.FlowNode -> {
                    ids.add(el.id)
                    if (el is org.camunda.bpm.model.bpmn.instance.SubProcess) {
                        collectFromElements(el.flowElements.toList(), ids)
                    }
                }
                else -> Unit
            }
        }
    }
}
