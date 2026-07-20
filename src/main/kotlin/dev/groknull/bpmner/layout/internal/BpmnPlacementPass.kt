/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.ElkSkeleton
import dev.groknull.bpmner.layout.internal.placement.ArtifactPlacement
import dev.groknull.bpmner.layout.internal.placement.AssociationEdges
import dev.groknull.bpmner.layout.internal.placement.BoundaryLabelPlacement
import dev.groknull.bpmner.layout.internal.placement.BoundaryShapePlacement
import dev.groknull.bpmner.layout.internal.placement.CollaborationShapePlacement
import dev.groknull.bpmner.layout.internal.placement.EdgeTerminalTailGuard
import dev.groknull.bpmner.layout.internal.placement.ElkLayoutResultCopy
import dev.groknull.bpmner.layout.internal.placement.ExceptionEdgeRoutes
import dev.groknull.bpmner.layout.internal.placement.ExternalBlackBoxBandPlacement
import dev.groknull.bpmner.layout.internal.placement.HandlerComponentAlignment
import dev.groknull.bpmner.layout.internal.placement.LabelMetrics
import dev.groknull.bpmner.layout.internal.placement.LabelWrap
import dev.groknull.bpmner.layout.internal.placement.LoopBackEdgeArcs
import dev.groknull.bpmner.layout.internal.placement.NodeShapeCopy
import dev.groknull.bpmner.layout.internal.placement.PlacementContext
import dev.groknull.bpmner.layout.internal.placement.SequenceEdgeElkCopy
import dev.groknull.bpmner.layout.internal.placement.SubprocessEndStraddle
import dev.groknull.bpmner.layout.internal.placement.SubprocessSpineCentring
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode

/**
 * Applies BPMN placement conventions to the mutable shared state context.
 *
 * Reads the ELK skeleton's coordinates as fixed input and applies each
 * BPMN placement convention (boundary shapes on bottom, labels below nodes,
 * baseline snap, etc.) using an ordered pipeline of processors.
 *
 * Outputs a [PlacedLayout] containing final shape bounds, label bounds,
 * and waypoints for sequence flows and associations.
 */
internal object BpmnPlacementPass {

    /** Simple rectangle (top-left corner + dimensions). */
    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double)

    /** Simple 2-D point. */
    data class Point(val x: Double, val y: Double)

    /**
     * The placement-pass → writer seam.
     * Every field is keyed by BPMN element ID.
     *
     * @param shapes  Final shape bounds (top-left x/y, width, height) for every node shape.
     * @param labels  Final label bounds for each element that has a name, keyed by owner ID.
     * @param edges   Final waypoint lists for sequence flows and associations, keyed by flow ID.
     * @param expanded Set of SubProcess IDs whose BPMNShape must carry isExpanded=true.
     */
    data class PlacedLayout(
        val shapes: Map<String, Rect>,
        val labels: Map<String, Rect>,
        val edges: Map<String, List<Point>>,
        val expanded: Set<String>,
    )

    /** DEFAULT_LABEL_SIZE width */
    internal const val LABEL_WIDTH = 90.0

    /** Width of sequence-flow edge labels, widened to prevent text wrapping/splitting. */
    internal const val EDGE_LABEL_WIDTH = 120.0

    /** DEFAULT_LABEL_SIZE height */
    internal const val LABEL_HEIGHT = 20.0

    /** Vertical gap between a node's bottom edge and the top of its external label. */
    internal const val LABEL_GAP_BELOW = 2.0

    /** Sub-pixel threshold below which a position change is treated as no-op. */
    internal const val POSITION_EPSILON = 0.5

    fun place(model: BpmnModelInstance, skeleton: ElkSkeleton): PlacedLayout {
        val ctx = PlacementContext(
            model = model,
            skeleton = skeleton,
            shapes = mutableMapOf(),
            labels = mutableMapOf(),
            edges = mutableMapOf(),
            expanded = mutableSetOf(),
        )
        run(ctx)
        return PlacedLayout(ctx.shapes, ctx.labels, ctx.edges, ctx.expanded)
    }

    /** Runs the ordered processor pipeline; exposed for tests that need to inspect the context. */
    internal fun run(ctx: PlacementContext) {
        pipeline.forEach { it.process(ctx) }
    }

    private val pipeline = listOf(
        NodeShapeCopy,
        HandlerComponentAlignment.Move,
        SubprocessEndStraddle.Move,
        SubprocessSpineCentring.Move,
        BoundaryShapePlacement,
        LoopBackEdgeArcs,
        ExceptionEdgeRoutes,
        SequenceEdgeElkCopy,
        HandlerComponentAlignment.Repair,
        SubprocessEndStraddle.Repair,
        SubprocessSpineCentring.Repair,
        CollaborationShapePlacement,
        EdgeTerminalTailGuard,
        ElkLayoutResultCopy,
        ExternalBlackBoxBandPlacement,
        BoundaryLabelPlacement,
        ArtifactPlacement,
        AssociationEdges,
    )

    /**
     * Accumulates parent ELK node offsets to convert a node's ELK-relative coordinates
     * into absolute canvas coordinates for BPMN-DI.
     *
     * The root graph node has a null identifier; the walk stops there.
     * This is the single coordinate-translation path shared with [ElkToBpmnDiWriter].
     */
    internal fun absolutePosition(node: ElkNode): Pair<Double, Double> {
        var x = node.x
        var y = node.y
        var parent = node.parent
        while (parent != null && parent.identifier != null) {
            x += parent.x
            y += parent.y
            parent = parent.parent
        }
        return x to y
    }

    /**
     * ELK reports edge section coordinates relative to the edge's containing node (the
     * lowest common ancestor of its endpoints). For an edge inside a subprocess compound
     * node, that container is offset from the root, so its waypoints must be shifted by the
     * container's absolute position to become canvas-absolute — the edge equivalent of
     * [absolutePosition]. The root graph has a null identifier and contributes no offset.
     */
    internal fun edgeContainerOffset(edge: ElkEdge): Pair<Double, Double> {
        val container = edge.containingNode ?: return 0.0 to 0.0
        if (container.identifier == null) return 0.0 to 0.0 // root graph
        return absolutePosition(container)
    }

    /**
     * Estimates label dimensions for [name] given [maxWidth] using the frozen
     * advance-width table ([LabelMetrics]) and the diagram-js semantic wrap
     * algorithm ([LabelWrap]).
     *
     * Returns (width, height) where width is the maximum fitted-line width (capped at
     * [maxWidth]) and height is `lineCount × LINE_HEIGHT` floored at [LABEL_HEIGHT].
     *
     * Returning the actual fitted width rather than always [maxWidth] lets single-line
     * labels report their true rendered width, avoiding over-tall boxes that shift the
     * visible text upward in bpmn-js's top-anchored label layout.
     */
    internal fun estimateLabelDimensions(name: String, maxWidth: Double): Pair<Double, Double> {
        if (name.isBlank()) return 0.0 to 0.0
        val result = LabelWrap.layout(name, maxWidth)
        val width = minOf(result.maxLineWidth, maxWidth)
        val height = maxOf(LABEL_HEIGHT, result.lineCount * LabelMetrics.LINE_HEIGHT)
        return width to height
    }
}
