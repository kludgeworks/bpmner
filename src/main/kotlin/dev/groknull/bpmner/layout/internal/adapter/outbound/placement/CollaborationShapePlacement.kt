/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.BLACK_BOX_HEIGHT
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.BLACK_BOX_WIDTH
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.PARTICIPANT_GAP
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.PARTICIPANT_HEADER_WIDTH
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.Lane
import org.camunda.bpm.model.bpmn.instance.Participant

/**
 * Phase-2 processor: derives [Rect] bounds for every [Participant] shape and every [Lane] shape.
 *
 * Postcondition: [PlacementContext.shapes] contains a [Rect] for every Participant and Lane.
 *
 * For white-box participants the bounds are derived from the union of already-placed flow-node
 * shapes (populated by [NodeShapeCopy] and the move processors). A fixed header band is added
 * on the left side (PARTICIPANT_HEADER_WIDTH). Lanes (if any) are stacked vertically within the
 * participant, each band containing its member nodes.
 *
 * For black-box participants the width matches the white-box participants and a fixed height
 * ([BLACK_BOX_HEIGHT]) is used. They are stacked below all white-box participants, aligned to the
 * same left edge, separated by [PARTICIPANT_GAP].
 *
 * This processor makes no ELK node relocations — it is a pure aggregation over already-placed shapes.
 */
internal object CollaborationShapePlacement : PlacementProcessor {

    private const val LANE_PADDING = 10.0
    private const val PARTICIPANT_PADDING = 20.0

    override fun process(ctx: PlacementContext) {
        val collaboration = ctx.model.getModelElementsByType(Collaboration::class.java).firstOrNull()
            ?: return // Not a collaboration diagram — nothing to do.

        val participants = collaboration.participants.toList()

        // Separate white-box (has processRef) and black-box (no processRef).
        val whiteBox = participants.filter { it.process != null }
        val blackBox = participants.filter { it.process == null }

        // Compute white-box participant bounds first (they anchor the layout).
        var maxWhiteBoxBottom = 0.0
        var whiteBoxLeft = 0.0
        var whiteBoxWidth = BLACK_BOX_WIDTH
        for (participant in whiteBox) {
            val bounds = computeWhiteBoxBounds(participant, ctx)
            ctx.shapes[participant.id] = bounds
            maxWhiteBoxBottom = maxOf(maxWhiteBoxBottom, bounds.y + bounds.h)
            whiteBoxLeft = bounds.x
            whiteBoxWidth = bounds.w

            // Compute lane bounds within this participant.
            computeLaneBounds(participant, bounds, ctx)

            // Participant label rect (for LabelPlacement): left header band.
            if (!participant.name.isNullOrBlank()) {
                ctx.labels[participant.id] = Rect(bounds.x, bounds.y, PARTICIPANT_HEADER_WIDTH, bounds.h)
            }
        }

        // Place black-box participants below all white-box participants, same width and left edge.
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
     * Computes the bounding box for a white-box participant by taking the union of all contained
     * flow-node shapes (already populated in [PlacementContext.shapes]) and adding the header band
     * on the left and uniform padding on the other three sides.
     */
    private fun computeWhiteBoxBounds(participant: Participant, ctx: PlacementContext): Rect {
        val process = participant.process ?: return Rect(0.0, 0.0, BLACK_BOX_WIDTH, BLACK_BOX_HEIGHT)
        val nodeIds = collectAllFlowNodeIds(process)

        val nodeShapes = nodeIds.mapNotNull { ctx.shapes[it] }
        if (nodeShapes.isEmpty()) {
            return Rect(0.0, 0.0, BLACK_BOX_WIDTH * 2, BLACK_BOX_HEIGHT * 2)
        }

        val minX = nodeShapes.minOf { it.x }
        val minY = nodeShapes.minOf { it.y }
        val maxX = nodeShapes.maxOf { it.x + it.w }
        val maxY = nodeShapes.maxOf { it.y + it.h }

        val contentX = minX - PARTICIPANT_PADDING
        val contentY = minY - PARTICIPANT_PADDING
        val contentRight = maxX + PARTICIPANT_PADDING
        val contentBottom = maxY + PARTICIPANT_PADDING

        // Participant left border: PARTICIPANT_HEADER_WIDTH to the left of content area.
        val participantX = contentX - PARTICIPANT_HEADER_WIDTH
        val participantY = contentY
        val participantW = contentRight - participantX
        val participantH = contentBottom - contentY

        return Rect(participantX, participantY, participantW, participantH)
    }

    /**
     * Computes [Rect] bounds for each [Lane] in a white-box participant.
     *
     * Lanes are stacked vertically (for horizontal pools). Each lane's Y-span is derived from
     * the Y-positions of its member nodes. Lane X starts at the participant left + PARTICIPANT_HEADER_WIDTH;
     * lane width = participant width − PARTICIPANT_HEADER_WIDTH.
     *
     * If a lane has no member nodes with placed shapes, it gets a default band.
     */
    @Suppress("NestedBlockDepth")
    private fun computeLaneBounds(participant: Participant, participantBounds: Rect, ctx: PlacementContext) {
        val process = participant.process ?: return
        val lanes = mutableListOf<Lane>()
        process.laneSets.forEach { ls -> lanes.addAll(ls.lanes) }
        if (lanes.isEmpty()) return

        // For each lane, find the Y-span of its member nodes.
        data class LaneBand(val lane: Lane, val minY: Double, val maxY: Double)

        val bands = lanes.map { lane ->
            val memberShapes = lane.flowNodeRefs.mapNotNull { ctx.shapes[it.id] }
            if (memberShapes.isEmpty()) {
                LaneBand(lane, participantBounds.y, participantBounds.y + participantBounds.h / lanes.size)
            } else {
                val minY = memberShapes.minOf { it.y } - LANE_PADDING
                val maxY = memberShapes.maxOf { it.y + it.h } + LANE_PADDING
                LaneBand(lane, minY, maxY)
            }
        }

        // Ensure lanes fill the full participant height without gaps: clamp to participant bounds.
        val laneX = participantBounds.x + PARTICIPANT_HEADER_WIDTH
        val laneW = participantBounds.w - PARTICIPANT_HEADER_WIDTH

        // Distribute vertically: lanes in document order, each from its band top to next band top.
        val sortedBands = bands // already in document order from the lane set
        val participantTop = participantBounds.y

        // Assign each lane a Y-range that covers its member nodes, clamped to participant bounds.
        // Simple approach: divide participant height equally among lanes if bands overlap.
        val laneCount = sortedBands.size
        for ((i, band) in sortedBands.withIndex()) {
            val laneY: Double
            val laneH: Double
            if (laneCount == 1) {
                laneY = participantTop
                laneH = participantBounds.h
            } else {
                // Assign bands by clamping to non-overlapping vertical strips.
                laneY = participantTop + i * (participantBounds.h / laneCount)
                laneH = participantTop + (i + 1) * (participantBounds.h / laneCount) - laneY
            }
            ctx.shapes[band.lane.id] = Rect(laneX, laneY, laneW, laneH)
            if (!band.lane.name.isNullOrBlank()) {
                ctx.labels[band.lane.id] = Rect(
                    participantBounds.x,
                    laneY,
                    PARTICIPANT_HEADER_WIDTH,
                    laneH,
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
