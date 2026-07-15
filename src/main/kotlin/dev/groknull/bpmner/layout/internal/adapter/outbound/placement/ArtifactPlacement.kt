/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import org.camunda.bpm.model.bpmn.instance.Association
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Group
import org.camunda.bpm.model.bpmn.instance.TextAnnotation

/**
 * Pipeline entry 13 — decoration: text annotation and group sidecar geometry.
 *
 * Postcondition: every [TextAnnotation] is placed below-and-right of its associated element
 * (if any association exists) or stacked below the skeleton at [ARTIFACT_MARGIN] intervals.
 * Every [Group] is placed as a padded bounding box around the flow-node shapes.
 */
internal object ArtifactPlacement : PlacementProcessor {

    private const val ARTIFACT_MARGIN = 30.0
    private const val GROUP_PADDING = 25.0
    private const val GROUP_NEST_STEP = 15.0

    override fun process(ctx: PlacementContext) {
        val skeletonBottom = ctx.shapes.values.maxOfOrNull { it.y + it.h } ?: 0.0

        val annotationHost = mutableMapOf<String, String>()
        for (assoc in ctx.model.getModelElementsByType(Association::class.java)) {
            val src = assoc.source?.id
            val tgt = assoc.target?.id
            val annId = listOf(src, tgt).firstOrNull { id -> ctx.shapes[id] == null && id != null }
            val hostId = listOf(src, tgt).firstOrNull { it != annId }
            if (annId != null && hostId != null) annotationHost[annId] = hostId
        }

        placeGroups(ctx)
        placeAnnotations(ctx, annotationHost, skeletonBottom)
    }

    private fun placeGroups(ctx: PlacementContext) {
        val groups = ctx.model.getModelElementsByType(Group::class.java).sortedBy { it.id }
        if (groups.isEmpty()) return

        val flowNodeIds = ctx.model.getModelElementsByType(FlowNode::class.java)
            .mapTo(mutableSetOf()) { it.id }
        val flowShapes = ctx.shapes.filterKeys { it in flowNodeIds }.values
        if (flowShapes.isEmpty()) return

        val minX = flowShapes.minOf { it.x }
        val minY = flowShapes.minOf { it.y }
        val maxX = flowShapes.maxOf { it.x + it.w }
        val maxY = flowShapes.maxOf { it.y + it.h }

        groups.forEachIndexed { index, group ->
            val pad = GROUP_PADDING + index * GROUP_NEST_STEP
            ctx.shapes[group.id] = Rect(
                minX - pad,
                minY - pad,
                (maxX - minX) + 2 * pad,
                (maxY - minY) + 2 * pad,
            )
        }
    }

    private fun placeAnnotations(
        ctx: PlacementContext,
        annotationHost: Map<String, String>,
        skeletonBottom: Double,
    ) {
        val groupBottom = ctx.model.getModelElementsByType(Group::class.java)
            .mapNotNull { ctx.shapes[it.id] }
            .maxOfOrNull { it.y + it.h }
        var fallbackY = skeletonBottom + ARTIFACT_MARGIN
        ctx.model.getModelElementsByType(TextAnnotation::class.java)
            .sortedBy { it.id }
            .forEach { ann ->
                val elkNode = ctx.skeleton.nodeMap[ann.id] ?: return@forEach
                val host = annotationHost[ann.id]?.let { ctx.shapes[it] }
                if (host != null) {
                    val belowHost = host.y + host.h + ARTIFACT_MARGIN
                    val belowGroup = groupBottom?.let { it + ARTIFACT_MARGIN } ?: belowHost
                    ctx.shapes[ann.id] = Rect(
                        host.x + host.w + ARTIFACT_MARGIN,
                        maxOf(belowHost, belowGroup),
                        elkNode.width,
                        elkNode.height,
                    )
                } else {
                    ctx.shapes[ann.id] = Rect(0.0, fallbackY, elkNode.width, elkNode.height)
                    fallbackY += elkNode.height + ARTIFACT_MARGIN
                }
            }
    }
}
