/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.ElkSkeleton
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.ArtifactPlacement
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.AssociationEdges
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.BoundaryShapePlacement
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.ExceptionEdgeRoutes
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.HandlerComponentAlignment
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.LabelPlacement
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.LoopBackEdgeArcs
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.NodeShapeCopy
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.PlacementContext
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.SequenceEdgeElkCopy
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.SubprocessEndStraddle
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.SubprocessSpineCentring
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode

/**
 * Phase 2 — BPMN placement pass: deterministic, BPMN-aware decoration.
 *
 * Reads the ELK skeleton's coordinates as fixed/immutable input and applies each
 * BPMN placement convention as a named rule in one place, via an ordered
 * [PlacementProcessor][dev.groknull.bpmner.layout.internal.adapter.outbound.placement.PlacementProcessor]
 * pipeline (AD-557-14). Per AD-557-11 this pass NEVER relocates a node ELK placed except for
 * the three declared, paired, guarded moving conventions ledgered in PlacementContext.moves.
 *
 * Pipeline order (preserved exactly from the pre-extraction monolith):
 *  1. [NodeShapeCopy]                      — ELK copy (pure)
 *  2. [HandlerComponentAlignment.Move]     — declared move: rigid X-translation
 *  3. [SubprocessEndStraddle.Move]         — declared move: straddle
 *  4. [SubprocessSpineCentring.Move]       — declared move: Y-snap
 *  5. [BoundaryShapePlacement]             — decoration (AD-557-11 sanctioned)
 *  6. [LoopBackEdgeArcs]                   — bespoke edge (AD-557-12 extension)
 *  7. [ExceptionEdgeRoutes]               — bespoke edge (AD-557-12 sanctioned)
 *  8. [SequenceEdgeElkCopy]               — ELK copy (pure)
 *  9. [HandlerComponentAlignment.Repair]  — repair (reads ledger)
 * 10. [SubprocessEndStraddle.Repair]      — repair
 * 11. [SubprocessSpineCentring.Repair]    — repair
 * 12. [LabelPlacement]                    — decoration
 * 13. [ArtifactPlacement]                 — decoration
 * 14. [AssociationEdges]                  — decoration
 *
 * Outputs a [PlacedLayout] that is the only thing [ElkToBpmnDiWriter] reads.
 *
 * Constants are named (not magic), not configurable (AD-557-10 and ARCHITECTURE.md Non-goals).
 */
@Suppress("TooManyFunctions")
internal object BpmnPlacementPass {

    // ── Output types ──────────────────────────────────────────────────────────

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

    // ── Shared constants ──────────────────────────────────────────────────────

    /** bpmn-js DEFAULT_LABEL_SIZE width (see bpmn-js lib/util/LabelUtil.js). */
    internal const val LABEL_WIDTH = 90.0

    /** Width of sequence-flow edge labels, widened to prevent text wrapping/splitting. */
    internal const val EDGE_LABEL_WIDTH = 120.0

    /** bpmn-js DEFAULT_LABEL_SIZE height. */
    internal const val LABEL_HEIGHT = 20.0

    /** Vertical gap between a node's bottom edge and the top of its external label. */
    internal const val LABEL_GAP_BELOW = 2.0

    /** Vertical gap between an edge label's bottom and the edge it sits above. */
    internal const val EDGE_LABEL_GAP_ABOVE = 4.0

    /** Sub-pixel threshold below which a position change is treated as no-op. */
    internal const val POSITION_EPSILON = 0.5

    // ── Pipeline constants ────────────────────────────────────────────────────

    private const val CHAR_WIDTH = 6.5
    private const val SPACE_WIDTH = 4.0
    private const val LINE_HEIGHT = 14.0

    // ── Entry points ──────────────────────────────────────────────────────────

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
        LabelPlacement,
        ArtifactPlacement,
        AssociationEdges,
    )

    // ── Shared coordinate helpers ─────────────────────────────────────────────

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

    // ── Shared label geometry helpers ─────────────────────────────────────────

    /** Segment record used by LabelPlacement when finding the longest horizontal segment. */
    internal data class HorizontalSegment(val mid: Point, val startX: Double, val endX: Double)

    /**
     * Estimates label dimensions for [name] given [maxWidth]: wraps words at [maxWidth]
     * using [CHAR_WIDTH] per character and returns (width, height).
     */
    internal fun estimateLabelDimensions(name: String, maxWidth: Double): Pair<Double, Double> {
        val words = name.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Pair(0.0, 0.0)

        val longestWordWidth = words.maxOf { it.length } * CHAR_WIDTH
        val finalWidth = maxOf(maxWidth, longestWordWidth)

        var lines = 1
        var currentLineLength = 0.0

        for (word in words) {
            val wordWidth = word.length * CHAR_WIDTH
            if (currentLineLength == 0.0) {
                currentLineLength = wordWidth
            } else {
                if (currentLineLength + SPACE_WIDTH + wordWidth > finalWidth) {
                    lines++
                    currentLineLength = wordWidth
                } else {
                    currentLineLength += SPACE_WIDTH + wordWidth
                }
            }
        }

        val finalHeight = maxOf(LABEL_HEIGHT, lines * LINE_HEIGHT)
        return Pair(finalWidth, finalHeight)
    }
}
