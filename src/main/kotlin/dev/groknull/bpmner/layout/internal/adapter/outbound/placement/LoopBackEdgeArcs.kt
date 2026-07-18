/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.SubProcess

/**
 * Bespoke edge: over-the-top arcs for loop-back sequence flows.
 *
 * Every flow in loopBackFlowIds has an orthogonal arc that exits the source's top, rises above
 * the subprocess top-padding arc lane, traverses left to above the target, and descends into
 * the target's top.
 *
 * Fallback: [LOOP_ARC_CLEARANCE] above the topmost node when no enclosing subprocess is found.
 */
internal object LoopBackEdgeArcs : PlacementProcessor {

    /** Clearance above the topmost node when no subprocess rect is available. */
    internal const val LOOP_ARC_CLEARANCE = 30.0

    override fun process(ctx: PlacementContext) {
        if (ctx.skeleton.loopBackFlowIds.isEmpty()) return
        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.id in ctx.skeleton.loopBackFlowIds }
            .sortedBy { it.id }
            .forEach { sf -> routeAndStore(sf, ctx) }
    }

    /**
     * Recomputes [sf]'s loop-back arc from its endpoints' current placed shapes and stores it
     * in [PlacementContext.edges]. No-op if either endpoint is unplaced.
     *
     * Exposed so [CollaborationShapePlacement] can re-run this after lane placement moves an
     * endpoint's shape, keeping the arc attached instead of going stale.
     */
    internal fun routeAndStore(sf: SequenceFlow, ctx: PlacementContext) {
        val srcRect = ctx.shapes[sf.source?.id ?: return] ?: return
        val tgtRect = ctx.shapes[sf.target?.id ?: return] ?: return
        val sub = ctx.model.getModelElementsByType(SubProcess::class.java)
            .firstOrNull { sub ->
                sub.flowElements.filterIsInstance<SequenceFlow>().any { it.id == sf.id }
            }
        val subRect = sub?.let { ctx.shapes[it.id] }
        ctx.edges[sf.id] = routeLoopBackEdge(srcRect, tgtRect, subRect)
    }

    /**
     * Computes waypoints for one loop-back arc between placed [srcRect] and [tgtRect].
     *
     * Uses [subRect]'s top-padding band as the arc lane when the flow is inside an enclosing
     * subprocess, otherwise [LOOP_ARC_CLEARANCE] above the topmost of the two endpoints.
     */
    internal fun routeLoopBackEdge(srcRect: Rect, tgtRect: Rect, subRect: Rect?): List<Point> {
        val arcLaneY = if (subRect != null) {
            subRect.y + BpmnToElkMapper.SUBPROCESS_TOP_PADDING / 2.0
        } else {
            minOf(srcRect.y, tgtRect.y) - LOOP_ARC_CLEARANCE
        }
        val srcCx = srcRect.x + srcRect.w / 2.0
        val tgtCx = tgtRect.x + tgtRect.w / 2.0
        return listOf(
            Point(srcCx, srcRect.y),
            Point(srcCx, arcLaneY),
            Point(tgtCx, arcLaneY),
            Point(tgtCx, tgtRect.y),
        )
    }
}
