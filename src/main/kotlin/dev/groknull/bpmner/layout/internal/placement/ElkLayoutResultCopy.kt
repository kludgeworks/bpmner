/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.MessageFlow
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkLabel

/** Copies ELK-produced label bounds and message-flow sections into the writer seam. */
internal object ElkLayoutResultCopy : PlacementProcessor {

    override fun process(ctx: PlacementContext) {
        copyNodeLabels(ctx)
        copyEdgeLabelsAndMessageSections(ctx)
    }

    private fun copyNodeLabels(ctx: PlacementContext) {
        ctx.model.getModelElementsByType(FlowNode::class.java)
            .filter { it !is BoundaryEvent && !it.name.isNullOrBlank() }
            .sortedBy { it.id }
            .forEach { node ->
                val elkNode = ctx.skeleton.nodeMap[node.id] ?: return@forEach
                val label = elkNode.labels.firstOrNull() ?: return@forEach
                val shape = ctx.shapes[node.id] ?: return@forEach
                ctx.labels[node.id] = Rect(shape.x + label.x, shape.y + label.y, label.width, label.height)
            }
    }

    private fun copyEdgeLabelsAndMessageSections(ctx: PlacementContext) {
        val edgeIds = ctx.model.getModelElementsByType(SequenceFlow::class.java).map { it.id } +
            ctx.model.getModelElementsByType(MessageFlow::class.java).map { it.id }
        edgeIds.sorted().forEach { id ->
            val edge = ctx.skeleton.edgeMap[id] ?: return@forEach
            edge.labels.firstOrNull()?.let { label -> ctx.labels[id] = labelBounds(edge, label) }
            if (id !in ctx.edges && edge.sections.isNotEmpty()) ctx.edges[id] = waypoints(edge)
        }
    }

    private fun labelBounds(edge: ElkEdge, label: ElkLabel): Rect {
        val (x, y) = BpmnPlacementPass.edgeContainerOffset(edge)
        return Rect(x + label.x, y + label.y, label.width, label.height)
    }

    private fun waypoints(edge: ElkEdge): List<Point> {
        val (x, y) = BpmnPlacementPass.edgeContainerOffset(edge)
        return edge.sections.flatMap { section ->
            buildList {
                add(Point(section.startX + x, section.startY + y))
                section.bendPoints.forEach { add(Point(it.x + x, it.y + y)) }
                add(Point(section.endX + x, section.endY + y))
            }
        }
    }
}
