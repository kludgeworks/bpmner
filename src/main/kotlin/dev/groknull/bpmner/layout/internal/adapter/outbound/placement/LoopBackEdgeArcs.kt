/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.SubProcess

/**
 * Pipeline entry 6 — bespoke edge: over-the-top arcs for loop-back sequence flows (AD-557-12).
 *
 * Postcondition: every flow in [dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.ElkSkeleton.loopBackFlowIds]
 * has an orthogonal arc that exits the source's top, rises above the subprocess top-padding arc
 * lane ([BpmnToElkMapper.SUBPROCESS_TOP_PADDING] / 2 above the subprocess top), traverses left
 * to above the target, and descends into the target's top. Entries are added to [PlacementContext.edges]
 * before [SequenceEdgeElkCopy] runs so the ELK copy never overwrites a bespoke arc.
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
            .forEach { sf ->
                val srcRect = ctx.shapes[sf.source?.id ?: return@forEach] ?: return@forEach
                val tgtRect = ctx.shapes[sf.target?.id ?: return@forEach] ?: return@forEach
                val sub = ctx.model.getModelElementsByType(SubProcess::class.java)
                    .firstOrNull { sub ->
                        sub.flowElements.filterIsInstance<SequenceFlow>().any { it.id == sf.id }
                    }
                val subRect = sub?.let { ctx.shapes[it.id] }
                val arcLaneY = if (subRect != null) {
                    subRect.y + BpmnToElkMapper.SUBPROCESS_TOP_PADDING / 2.0
                } else {
                    minOf(srcRect.y, tgtRect.y) - LOOP_ARC_CLEARANCE
                }
                val srcCx = srcRect.x + srcRect.w / 2.0
                val tgtCx = tgtRect.x + tgtRect.w / 2.0
                ctx.edges[sf.id] = listOf(
                    Point(srcCx, srcRect.y),
                    Point(srcCx, arcLaneY),
                    Point(tgtCx, arcLaneY),
                    Point(tgtCx, tgtRect.y),
                )
            }
    }
}
