/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.EDGE_LABEL_WIDTH
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.estimateLabelDimensions
import org.camunda.bpm.model.bpmn.instance.BaseElement
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.MessageFlow
import org.camunda.bpm.model.bpmn.instance.Participant

/**
 * Phase-2 processor: derives waypoints for every [MessageFlow] in the model.
 *
 * Routes message flows orthogonally (AD-557-11 / AD-557-04):
 *   - Vertically-stacked pools: exit/enter perpendicular to the pool boundary, with an
 *     L-shape through the inter-pool gap when source and target have different x-centres.
 *   - Horizontally-arranged pools: straight horizontal line, right-centre → left-centre.
 *
 * Bidirectional offset: when a non-participant node is both the source of one vertical
 * message flow and the target of another on the same x-centre, the two flows would
 * overlap. An offset ([BIDIRECTIONAL_OFFSET]) is applied — outbound flow shifts right,
 * inbound flow shifts left — so the parallel lines are visually distinct.
 *
 * No ELK edges are created for message flows; this processor is the sole owner of their routing.
 */
internal object MessageFlowEdges : PlacementProcessor {

    /** Lateral offset applied to each side of a bidirectional vertical message flow pair. */
    private const val BIDIRECTIONAL_OFFSET = 7.5

    override fun process(ctx: PlacementContext) {
        val collaboration = ctx.model.getModelElementsByType(Collaboration::class.java).firstOrNull()
            ?: return

        val flows = collaboration.messageFlows.toList()

        // Build a map from flow-node ID → owning participant shape, for gap-centre computation.
        val nodeToParticipant: Map<String, Rect> = collaboration.participants
            .mapNotNull { p ->
                val pShape = ctx.shapes[p.id] ?: return@mapNotNull null
                p.process?.flowElements
                    ?.filterIsInstance<FlowNode>()
                    ?.map { it.id to pShape }
            }
            .flatten()
            .toMap()

        // Detect x-corridors shared by more than one vertical flow — those need a lateral offset.
        val sharedCorridors = sharedVerticalCorridors(flows, ctx)

        flows.forEach { mf -> routeMessageFlow(mf, ctx, sharedCorridors, nodeToParticipant) }
    }

    /**
     * Computes the set of x-corridors (baseCx values) used by more than one vertical message flow.
     * Flows sharing a corridor would overlap without a lateral offset.
     */
    private fun sharedVerticalCorridors(flows: List<MessageFlow>, ctx: PlacementContext): Set<Double> {
        val counts = mutableMapOf<Double, Int>()
        flows.forEach { mf ->
            val srcShape = resolveShape(mf.source, ctx) ?: return@forEach
            val tgtShape = resolveShape(mf.target, ctx) ?: return@forEach
            val vertical = srcShape.y + srcShape.h <= tgtShape.y || tgtShape.y + tgtShape.h <= srcShape.y
            if (!vertical) return@forEach
            val srcCx = srcShape.x + srcShape.w / 2.0
            val tgtCx = tgtShape.x + tgtShape.w / 2.0
            val baseCx = when {
                mf.source is Participant -> tgtCx
                mf.target is Participant -> srcCx
                else -> srcCx
            }
            counts[baseCx] = (counts[baseCx] ?: 0) + 1
        }
        return counts.filterValues { it > 1 }.keys
    }

    private fun routeMessageFlow(
        mf: MessageFlow,
        ctx: PlacementContext,
        sharedCorridors: Set<Double>,
        nodeToParticipant: Map<String, Rect>,
    ) {
        val srcShape = resolveShape(mf.source, ctx) ?: return
        val tgtShape = resolveShape(mf.target, ctx) ?: return
        val srcId = (mf.source as? BaseElement)?.id
        val tgtId = (mf.target as? BaseElement)?.id
        val srcIsParticipant = mf.source is Participant
        val tgtIsParticipant = mf.target is Participant

        val vertical = srcShape.y + srcShape.h <= tgtShape.y || tgtShape.y + tgtShape.h <= srcShape.y
        val waypoints = if (vertical) {
            val srcCx = srcShape.x + srcShape.w / 2.0
            val tgtCx = tgtShape.x + tgtShape.w / 2.0
            val baseCx = when {
                srcIsParticipant -> tgtCx
                tgtIsParticipant -> srcCx
                else -> srcCx
            }
            val srcCy = srcShape.y + srcShape.h / 2.0
            val tgtCy = tgtShape.y + tgtShape.h / 2.0
            if (tgtCy > srcCy) {
                verticalDownWaypoints(
                    srcShape, tgtShape, srcId, tgtId,
                    srcCx, tgtCx, baseCx, srcIsParticipant, tgtIsParticipant,
                    sharedCorridors, nodeToParticipant,
                )
            } else {
                verticalUpWaypoints(
                    srcShape, tgtShape, srcId, tgtId,
                    srcCx, tgtCx, baseCx, srcIsParticipant, tgtIsParticipant,
                    sharedCorridors, nodeToParticipant,
                )
            }
        } else {
            val srcCy = srcShape.y + srcShape.h / 2.0
            val tgtCy = tgtShape.y + tgtShape.h / 2.0
            listOf(Point(srcShape.x + srcShape.w, srcCy), Point(tgtShape.x, tgtCy))
        }

        ctx.edges[mf.id] = waypoints

        if (!mf.name.isNullOrBlank()) {
            val midX = (waypoints.first().x + waypoints.last().x) / 2.0
            val midY = (waypoints.first().y + waypoints.last().y) / 2.0
            val (lw, lh) = estimateLabelDimensions(mf.name!!, EDGE_LABEL_WIDTH)
            ctx.labels[mf.id] = Rect(midX - lw / 2.0, midY - lh / 2.0, lw, lh)
        }
    }

    /** Waypoints for a vertical flow going downward (source in top pool, target in bottom pool). */
    @Suppress("LongParameterList")
    private fun verticalDownWaypoints(
        srcShape: Rect,
        tgtShape: Rect,
        srcId: String?,
        tgtId: String?,
        srcCx: Double,
        tgtCx: Double,
        baseCx: Double,
        srcIsParticipant: Boolean,
        tgtIsParticipant: Boolean,
        sharedCorridors: Set<Double>,
        nodeToParticipant: Map<String, Rect>,
    ): List<Point> {
        // Downward flow exits left of centre so it does not cross the returning upward flow.
        val offset = if (!srcIsParticipant && !tgtIsParticipant && baseCx in sharedCorridors) {
            -BIDIRECTIONAL_OFFSET
        } else {
            0.0
        }
        val exitX = baseCx + offset
        val srcExit = Point(exitX, srcShape.y + srcShape.h)
        val tgtEntry = Point(exitX, tgtShape.y)
        if (srcCx == tgtCx || srcIsParticipant || tgtIsParticipant) return listOf(srcExit, tgtEntry)
        val gapMidY = interPoolGapMidY(
            topPoolBottom = nodeToParticipant[srcId]?.let { it.y + it.h } ?: (srcShape.y + srcShape.h),
            botPoolTop = nodeToParticipant[tgtId]?.y ?: tgtShape.y,
        )
        return listOf(srcExit, Point(exitX, gapMidY), Point(tgtCx, gapMidY), Point(tgtCx, tgtShape.y))
    }

    /** Waypoints for a vertical flow going upward (source in bottom pool, target in top pool). */
    @Suppress("LongParameterList")
    private fun verticalUpWaypoints(
        srcShape: Rect,
        tgtShape: Rect,
        srcId: String?,
        tgtId: String?,
        srcCx: Double,
        tgtCx: Double,
        baseCx: Double,
        srcIsParticipant: Boolean,
        tgtIsParticipant: Boolean,
        sharedCorridors: Set<Double>,
        nodeToParticipant: Map<String, Rect>,
    ): List<Point> {
        // Upward (return) flow exits right of centre, mirroring the downward flow on the other side.
        val offset = if (!srcIsParticipant && !tgtIsParticipant && baseCx in sharedCorridors) {
            BIDIRECTIONAL_OFFSET
        } else {
            0.0
        }
        val exitX = baseCx + offset
        val srcExit = Point(exitX, srcShape.y)
        val tgtEntry = Point(exitX, tgtShape.y + tgtShape.h)
        if (srcCx == tgtCx || srcIsParticipant || tgtIsParticipant) return listOf(srcExit, tgtEntry)
        val gapMidY = interPoolGapMidY(
            topPoolBottom = nodeToParticipant[tgtId]?.let { it.y + it.h } ?: (tgtShape.y + tgtShape.h),
            botPoolTop = nodeToParticipant[srcId]?.y ?: srcShape.y,
        )
        // Final waypoint enters the target (top pool) from its bottom edge.
        return listOf(srcExit, Point(exitX, gapMidY), Point(tgtCx, gapMidY), Point(tgtCx, tgtShape.y + tgtShape.h))
    }

    /** Midpoint of the gap between two vertically-stacked pools. */
    private fun interPoolGapMidY(topPoolBottom: Double, botPoolTop: Double) =
        (topPoolBottom + botPoolTop) / 2.0

    /**
     * Resolves the shape [Rect] for a [org.camunda.bpm.model.bpmn.instance.InteractionNode].
     *
     * An [InteractionNode] may be a [org.camunda.bpm.model.bpmn.instance.FlowNode],
     * a [org.camunda.bpm.model.bpmn.instance.Participant], or (rarely) an
     * [org.camunda.bpm.model.bpmn.instance.EndPoint]. We look up the element's ID in
     * [PlacementContext.shapes] — which by this point contains both flow-node shapes
     * (from [NodeShapeCopy]) and participant shapes (from [CollaborationShapePlacement]).
     */
    private fun resolveShape(
        node: org.camunda.bpm.model.bpmn.instance.InteractionNode?,
        ctx: PlacementContext,
    ): Rect? {
        if (node == null) return null
        return ctx.shapes[(node as? BaseElement)?.id ?: return null]
    }
}
