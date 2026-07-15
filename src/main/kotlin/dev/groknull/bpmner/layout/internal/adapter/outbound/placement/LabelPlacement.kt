/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.EDGE_LABEL_GAP_ABOVE
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.EDGE_LABEL_WIDTH
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.LABEL_GAP_BELOW
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.LABEL_WIDTH
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.SequenceFlow

/**
 * Pipeline entry 12 — decoration: label placement.
 *
 * Postcondition: every named flow node has a label in [PlacementContext.labels] placed below its
 * shape (centred, bpmn-js DEFAULT_LABEL_SIZE 90×20 minimum); every named sequence flow has a label
 * centred on the longest horizontal segment of its edge polyline, nudged above the line.
 * No element's label is placed at the element's own coordinates (direct fix for BLOCK-557-3 symptom 1).
 */
internal object LabelPlacement : PlacementProcessor {

    override fun process(ctx: PlacementContext) {
        placeNodeLabels(ctx)
        placeSequenceFlowLabels(ctx)
    }

    private fun placeNodeLabels(ctx: PlacementContext) {
        ctx.model.getModelElementsByType(FlowNode::class.java)
            .filter { !it.name.isNullOrBlank() }
            .sortedBy { it.id }
            .forEach { flowNode ->
                val shape = ctx.shapes[flowNode.id] ?: return@forEach
                val name = flowNode.name ?: ""
                val (labelWidth, labelHeight) = BpmnPlacementPass.estimateLabelDimensions(name, LABEL_WIDTH)
                val labelX = shape.x + shape.w / 2.0 - labelWidth / 2.0
                val labelY = shape.y + shape.h + LABEL_GAP_BELOW
                ctx.labels[flowNode.id] = Rect(labelX, labelY, labelWidth, labelHeight)
            }
    }

    private fun placeSequenceFlowLabels(ctx: PlacementContext) {
        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { !it.name.isNullOrBlank() }
            .sortedBy { it.id }
            .forEach { sf ->
                val wps = ctx.edges[sf.id]?.takeIf { it.size >= 2 } ?: return@forEach
                val name = sf.name ?: ""
                val (labelWidth, labelHeight) = BpmnPlacementPass.estimateLabelDimensions(name, EDGE_LABEL_WIDTH)

                val horizSeg = findLongestHorizontalSegment(wps)
                val isBackward = wps.first().x > wps.last().x

                if (horizSeg != null) {
                    val labelY = if (isBackward) {
                        horizSeg.mid.y + EDGE_LABEL_GAP_ABOVE
                    } else {
                        horizSeg.mid.y - labelHeight - EDGE_LABEL_GAP_ABOVE
                    }
                    var labelX = horizSeg.mid.x - labelWidth / 2.0
                    if (isBackward) {
                        labelX = maxOf(labelX, horizSeg.endX + LABEL_MARGIN)
                    } else {
                        labelX = minOf(labelX, horizSeg.endX - labelWidth - LABEL_MARGIN)
                    }
                    ctx.labels[sf.id] = Rect(labelX, labelY, labelWidth, labelHeight)
                } else {
                    placeNonHorizontalEdgeLabel(sf.id, wps, name, ctx)
                }
            }
    }

    private fun placeNonHorizontalEdgeLabel(
        id: String,
        wps: List<Point>,
        name: String,
        ctx: PlacementContext,
    ) {
        val (labelWidth, labelHeight) = BpmnPlacementPass.estimateLabelDimensions(name, EDGE_LABEL_WIDTH)
        val isBackward = wps.first().x > wps.last().x
        val mid = polylineMidpoint(wps)
        if (isVerticalSegment(wps, mid)) {
            ctx.labels[id] = Rect(
                mid.x + EDGE_LABEL_GAP_ABOVE,
                mid.y - labelHeight / 2.0,
                labelWidth,
                labelHeight,
            )
        } else {
            val labelY = if (isBackward) {
                mid.y + EDGE_LABEL_GAP_ABOVE
            } else {
                mid.y - labelHeight - EDGE_LABEL_GAP_ABOVE
            }
            ctx.labels[id] = Rect(
                mid.x - labelWidth / 2.0,
                labelY,
                labelWidth,
                labelHeight,
            )
        }
    }

    private const val LABEL_MARGIN = 8.0
    private const val EPSILON = 0.001

    /** The point halfway along the total length of a polyline. */
    private fun polylineMidpoint(wps: List<Point>): Point {
        val total = (1 until wps.size).sumOf { i -> dist(wps[i - 1], wps[i]) }
        if (total == 0.0) return wps.first()
        var remaining = total / 2.0
        for (i in 1 until wps.size) {
            val seg = dist(wps[i - 1], wps[i])
            if (remaining <= seg) {
                val t = if (seg == 0.0) 0.0 else remaining / seg
                return Point(
                    wps[i - 1].x + (wps[i].x - wps[i - 1].x) * t,
                    wps[i - 1].y + (wps[i].y - wps[i - 1].y) * t,
                )
            }
            remaining -= seg
        }
        return wps.last()
    }

    private fun dist(a: Point, b: Point): Double = kotlin.math.hypot(b.x - a.x, b.y - a.y)

    private fun findLongestHorizontalSegment(wps: List<Point>): BpmnPlacementPass.HorizontalSegment? {
        var longestLen = -1.0
        var bestSeg: BpmnPlacementPass.HorizontalSegment? = null
        for (i in 1 until wps.size) {
            val p1 = wps[i - 1]
            val p2 = wps[i]
            if (kotlin.math.abs(p1.y - p2.y) < EPSILON) {
                val len = kotlin.math.abs(p2.x - p1.x)
                if (len > longestLen) {
                    longestLen = len
                    bestSeg = BpmnPlacementPass.HorizontalSegment(
                        Point((p1.x + p2.x) / 2.0, p1.y),
                        p1.x,
                        p2.x,
                    )
                }
            }
        }
        return bestSeg
    }

    private fun isVerticalSegment(wps: List<Point>, mid: Point): Boolean {
        for (i in 1 until wps.size) {
            val p1 = wps[i - 1]
            val p2 = wps[i]
            val minX = minOf(p1.x, p2.x)
            val maxX = maxOf(p1.x, p2.x)
            val minY = minOf(p1.y, p2.y)
            val maxY = maxOf(p1.y, p2.y)
            val inX = mid.x >= minX - EPSILON && mid.x <= maxX + EPSILON
            val inY = mid.y >= minY - EPSILON && mid.y <= maxY + EPSILON
            if (inX && inY) {
                return kotlin.math.abs(p1.x - p2.x) < EPSILON
            }
        }
        return false
    }
}
