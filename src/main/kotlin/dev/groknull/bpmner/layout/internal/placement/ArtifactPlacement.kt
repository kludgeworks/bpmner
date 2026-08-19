/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.MetadataSynthesis
import org.camunda.bpm.model.bpmn.instance.DataObjectReference
import org.camunda.bpm.model.bpmn.instance.DataStoreReference
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Group
import org.camunda.bpm.model.bpmn.instance.TextAnnotation

/**
 * Text annotation, data reference, and group sidecar geometry.
 *
 * A [TextAnnotation]/[DataObjectReference]/[DataStoreReference] with a real ELK-mapped comment
 * box (`BpmnToElkMapper.trackAnnotations`/`trackDataReferences`, group E) is already positioned
 * by ELK's own comment-attachment mechanism — this processor copies its final coordinates, the
 * same convention [NodeShapeCopy] uses for flow nodes. One with no association (or no mappable
 * host) keeps the pre-group-E fallback: stacked below the skeleton at [ARTIFACT_MARGIN]
 * intervals, sharing one running `y` across all three kinds so none land on top of each other.
 * Every [Group] is never mapped into the ELK graph at all — group bounding is a padded box
 * around the flow-node shapes, the legitimate projection-only survivor this processor still
 * owns directly.
 */
internal object ArtifactPlacement : PlacementProcessor {

    private const val ARTIFACT_MARGIN = 30.0
    private const val GROUP_PADDING = 25.0
    private const val GROUP_NEST_STEP = 15.0

    override fun process(ctx: PlacementContext) {
        placeGroups(ctx)
        placeMetadataArtifacts(ctx)
        placeCommentBoxArtifacts(ctx)
    }

    private fun placeMetadataArtifacts(ctx: PlacementContext) {
        val metadata = ctx.model.getModelElementsByType(TextAnnotation::class.java)
            .filter { it.id in MetadataSynthesis.markerIds }
            .sortedBy { MetadataSynthesis.markerIds.indexOf(it.id) }
        if (metadata.isEmpty()) return

        val skeletonMinX = ctx.shapes.values.minOfOrNull { it.x } ?: 0.0
        val skeletonWidth = (ctx.shapes.values.maxOfOrNull { it.x + it.w } ?: 0.0) - skeletonMinX
        var y = 0.0
        metadata.forEach { annotation ->
            val height = MetadataSynthesis.annotationHeight()
            val width = if (annotation.id == MetadataSynthesis.HEADER_ID) {
                skeletonWidth
            } else {
                MetadataSynthesis.annotationWidth(annotation)
            }
            ctx.shapes[annotation.id] = Rect(skeletonMinX, y, width, height)
            y += height
        }
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

    private fun placeCommentBoxArtifacts(ctx: PlacementContext) {
        val ids = ctx.model.getModelElementsByType(TextAnnotation::class.java).map { it.id } +
            ctx.model.getModelElementsByType(DataObjectReference::class.java).map { it.id } +
            ctx.model.getModelElementsByType(DataStoreReference::class.java).map { it.id }

        val skeletonBottom = ctx.shapes.values.maxOfOrNull { it.y + it.h } ?: 0.0
        var fallbackY = skeletonBottom + ARTIFACT_MARGIN
        ids.sorted().forEach { id ->
            if (id in MetadataSynthesis.markerIds) return@forEach
            val elkNode = ctx.skeleton.nodeMap[id] ?: return@forEach
            if (elkNode.parent != null) {
                // Real comment box: ELK already decided its position (above/below its host,
                // clear of the host's margin) — copy it rather than recompute it.
                val (ax, ay) = BpmnPlacementPass.absolutePosition(elkNode)
                ctx.shapes[id] = Rect(ax, ay, elkNode.width, elkNode.height)
            } else {
                // Detached placeholder (no association, or host ELK never mapped): stack
                // below the skeleton, the pre-group-E fallback.
                ctx.shapes[id] = Rect(0.0, fallbackY, elkNode.width, elkNode.height)
                fallbackY += elkNode.height + ARTIFACT_MARGIN
            }
        }
    }
}
