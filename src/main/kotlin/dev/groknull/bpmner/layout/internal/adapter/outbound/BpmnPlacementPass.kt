/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.ElkSkeleton
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Group
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.SubProcess
import org.camunda.bpm.model.bpmn.instance.TextAnnotation
import org.eclipse.elk.graph.ElkNode
import kotlin.math.abs

/**
 * Phase 2 — BPMN placement pass: deterministic, BPMN-aware decoration.
 *
 * Reads the ELK skeleton's coordinates as fixed/immutable input and applies each
 * BPMN placement convention as a NAMED RULE in one place. This is the AD-557-10
 * component that owns every convention ELK cannot provide:
 *
 * - [placeNodeShapes] — all non-boundary flow nodes at absolute ELK coordinates.
 * - [placeBoundaryShapes] — boundary-event shapes on the host's BOTTOM edge, straddling,
 *   evenly distributed for multiple attachments.
 * - [reconcileExceptionEdges] — reconciles the ELK-routed exception edge's first waypoint
 *   to the placed boundary shape.
 * - [placeLabels] — fixed 90×20 box below nodes / at edge-waypoint mid.
 * - [placeArtifacts] — text annotations and groups as sidecar geometry.
 * - [snapBaseline] — snaps the primary-flow shapes to a single centre Y.
 *
 * Outputs a [PlacedLayout] that is the only thing [ElkToBpmnDiWriter] reads.
 *
 * Constants are named (not magic), not configurable (AD-557-10 and ARCHITECTURE.md Non-goals).
 */
@Suppress("TooManyFunctions")
internal object BpmnPlacementPass {

    // ── Output type ──────────────────────────────────────────────────────────

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

    // ── Constants (named rules, not magic numbers) ────────────────────────────

    /** bpmn-js DEFAULT_LABEL_SIZE width (see bpmn-js lib/util/LabelUtil.js). */
    internal const val LABEL_WIDTH = 90.0

    /** bpmn-js DEFAULT_LABEL_SIZE height. */
    internal const val LABEL_HEIGHT = 20.0

    /** Vertical gap between a node's bottom edge and the top of its external label. */
    internal const val LABEL_GAP_BELOW = 2.0

    /** Horizontal indent for edge labels from the waypoint midpoint (bpmn-js convention). */
    internal const val EDGE_LABEL_INDENT = 15.0

    /** Half of EVENT_SIZE — used for boundary straddle offset. */
    private const val BOUNDARY_HALF = BpmnToElkMapper.EVENT_SIZE / 2.0

    /** Minimum snap delta below which baseline adjustment is skipped (sub-pixel). */
    private const val BASELINE_SNAP_EPSILON = 0.01

    // ── Entry point ───────────────────────────────────────────────────────────

    fun place(model: BpmnModelInstance, skeleton: ElkSkeleton): PlacedLayout {
        val shapes = mutableMapOf<String, Rect>()
        val labels = mutableMapOf<String, Rect>()
        val edges = mutableMapOf<String, List<Point>>()
        val expanded = mutableSetOf<String>()

        placeNodeShapes(model, skeleton, shapes, expanded)
        placeBoundaryShapes(model, skeleton, shapes)
        reconcileExceptionEdges(model, skeleton, shapes, edges)
        placeSequenceEdgeWaypoints(model, skeleton, edges)
        placeLabels(model, shapes, edges, labels)
        placeArtifacts(model, skeleton, shapes)
        snapBaseline(model, shapes)
        placeAssociationEdges(model, shapes, edges)

        return PlacedLayout(shapes, labels, edges, expanded)
    }

    // ── Named rule 1: node shapes ─────────────────────────────────────────────

    /**
     * Copies ELK bounds for all non-boundary flow nodes through the single
     * absolute-coordinate path [absolutePosition].
     * SubProcess IDs are added to [expanded].
     */
    private fun placeNodeShapes(
        model: BpmnModelInstance,
        skeleton: ElkSkeleton,
        shapes: MutableMap<String, Rect>,
        expanded: MutableSet<String>,
    ) {
        for (flowNode in model.getModelElementsByType(FlowNode::class.java)
            .filter { it !is BoundaryEvent }
            .sortedBy { it.id }) {
            val elkNode = skeleton.nodeMap[flowNode.id] ?: continue
            val (ax, ay) = absolutePosition(elkNode)
            shapes[flowNode.id] = Rect(ax, ay, elkNode.width, elkNode.height)
            if (flowNode is SubProcess) expanded.add(flowNode.id)
        }
    }

    // ── Named rule 2: boundary shapes on host bottom edge ─────────────────────

    /**
     * Places each boundary-event SHAPE on its host's BOTTOM edge, straddling (centre
     * on the edge), evenly distributed for multiple attachments.
     *
     * The ELK-routed exception edge is reconciled to the placed shape in
     * [reconcileExceptionEdges]. This is the direct fix for BLOCK-557-3 symptom 2.
     *
     * Distribution rule: N boundaries on a host are spaced equidistantly along the
     * host's bottom edge, ordered by boundary event ID for determinism.
     */
    private fun placeBoundaryShapes(
        model: BpmnModelInstance,
        skeleton: ElkSkeleton,
        shapes: MutableMap<String, Rect>,
    ) {
        // Group boundaries by host ID, sorted for determinism
        val byHost = model.getModelElementsByType(BoundaryEvent::class.java)
            .sortedBy { it.id }
            .groupBy { it.attachedTo?.id }

        for ((hostId, boundaries) in byHost.filterKeys { it != null }) {
            val hostNode = skeleton.nodeMap[hostId] ?: continue
            val hostRect = shapes[hostId] ?: run {
                val (hx, hy) = absolutePosition(hostNode)
                Rect(hx, hy, hostNode.width, hostNode.height)
            }
            placeBoundariesOnHost(hostRect, boundaries, shapes)
        }
    }

    private fun placeBoundariesOnHost(
        hostRect: Rect,
        boundaries: List<BoundaryEvent>,
        shapes: MutableMap<String, Rect>,
    ) {
        val n = boundaries.size
        // Evenly distribute N boundaries along the host's bottom edge.
        val step = hostRect.w / (n + 1).toDouble()
        for ((index, be) in boundaries.withIndex()) {
            val centreX = hostRect.x + step * (index + 1)
            val centreY = hostRect.y + hostRect.h // bottom edge y
            // Shape top-left so the circle centre straddles the bottom edge
            val eventSize = BpmnToElkMapper.EVENT_SIZE
            shapes[be.id] = Rect(centreX - BOUNDARY_HALF, centreY - BOUNDARY_HALF, eventSize, eventSize)
        }
    }

    // ── Named rule 3: reconcile exception-edge endpoints to placed boundary ────

    /**
     * For each boundary event, reads the ELK-routed exception edge and reconciles its
     * first waypoint to the placed boundary shape's centre (and copies remaining
     * waypoints). The rest of the exception edge routing comes from ELK (crossing-min).
     *
     * Also records non-exception sequence-flow waypoints for [placeSequenceEdgeWaypoints].
     */
    private fun reconcileExceptionEdges(
        model: BpmnModelInstance,
        skeleton: ElkSkeleton,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
    ) {
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }

        model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.source?.id in boundaryIds }
            .sortedBy { it.id }
            .forEach { sf ->
                val boundaryId = sf.source?.id ?: return@forEach
                val elkEdge = skeleton.edgeMap[sf.id] ?: return@forEach
                val section = elkEdge.sections.firstOrNull() ?: return@forEach
                val bRect = shapes[boundaryId] ?: return@forEach
                // Start waypoint = boundary shape centre (placed by rule 2)
                val waypoints = mutableListOf(Point(bRect.x + bRect.w / 2.0, bRect.y + bRect.h / 2.0))
                section.bendPoints.mapTo(waypoints) { Point(it.x, it.y) }
                waypoints.add(Point(section.endX, section.endY))
                edges[sf.id] = waypoints
            }
    }

    // ── Named rule 4: sequence-flow waypoints from ELK ────────────────────────

    /**
     * For non-exception sequence flows, copies ELK routing sections as waypoints.
     * Exception edges are already handled by [reconcileExceptionEdges].
     */
    private fun placeSequenceEdgeWaypoints(
        model: BpmnModelInstance,
        skeleton: ElkSkeleton,
        edges: MutableMap<String, List<Point>>,
    ) {
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }

        model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.source?.id !in boundaryIds && it.id !in edges }
            .sortedBy { it.id }
            .forEach { sf ->
                val elkEdge = skeleton.edgeMap[sf.id] ?: return@forEach
                val section = elkEdge.sections.firstOrNull() ?: return@forEach
                val waypoints = mutableListOf(Point(section.startX, section.startY))
                section.bendPoints.mapTo(waypoints) { Point(it.x, it.y) }
                waypoints.add(Point(section.endX, section.endY))
                edges[sf.id] = waypoints
            }
    }

    // ── Named rule 5: labels below nodes / at edge-waypoint mid ───────────────

    /**
     * Places label bounds using the bpmn-js DEFAULT_LABEL_SIZE (90×20) convention:
     * - Node labels: centred below the node shape (x = shape.cx - 45, y = shape.bottom + gap).
     * - Edge labels: at the midpoint of the edge waypoints, offset by indent.
     *
     * Never copies the element's own coordinates — this is the direct fix for
     * BLOCK-557-3 symptom 1 (labels colliding with their nodes).
     */
    private fun placeLabels(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        edges: Map<String, List<Point>>,
        labels: MutableMap<String, Rect>,
    ) {
        // Node labels (flow nodes including boundaries)
        model.getModelElementsByType(FlowNode::class.java)
            .filter { !it.name.isNullOrBlank() }
            .sortedBy { it.id }
            .forEach { flowNode ->
                val shape = shapes[flowNode.id] ?: return@forEach
                val labelX = shape.x + shape.w / 2.0 - LABEL_WIDTH / 2.0
                val labelY = shape.y + shape.h + LABEL_GAP_BELOW
                labels[flowNode.id] = Rect(labelX, labelY, LABEL_WIDTH, LABEL_HEIGHT)
            }

        // Sequence-flow edge labels at waypoint midpoint
        model.getModelElementsByType(SequenceFlow::class.java)
            .filter { !it.name.isNullOrBlank() }
            .sortedBy { it.id }
            .forEach { sf ->
                val wps = edges[sf.id]?.takeIf { it.size >= 2 } ?: return@forEach
                val mid = wps[wps.size / 2]
                labels[sf.id] = Rect(mid.x + EDGE_LABEL_INDENT, mid.y, LABEL_WIDTH, LABEL_HEIGHT)
            }
    }

    // ── Named rule 6: artifacts as sidecar geometry ───────────────────────────

    /**
     * Places text annotations and groups as sidecar geometry off the skeleton.
     * Annotations are placed to the right of the rightmost skeleton node with a margin.
     * Groups use their placeholder size; their position is approximate (offset from origin).
     */
    private fun placeArtifacts(
        model: BpmnModelInstance,
        skeleton: ElkSkeleton,
        shapes: MutableMap<String, Rect>,
    ) {
        // Find rightmost x extent of skeleton shapes
        val rightmostX = shapes.values.maxOfOrNull { it.x + it.w } ?: 0.0
        val artifactStartX = rightmostX + ARTIFACT_MARGIN

        var annotationOffsetY = ARTIFACT_MARGIN
        for (ann in model.getModelElementsByType(TextAnnotation::class.java).sortedBy { it.id }) {
            val elkNode = skeleton.nodeMap[ann.id] ?: continue
            shapes[ann.id] = Rect(artifactStartX, annotationOffsetY, elkNode.width, elkNode.height)
            annotationOffsetY += elkNode.height + ARTIFACT_MARGIN
        }

        var groupOffsetY = ARTIFACT_MARGIN
        for (group in model.getModelElementsByType(Group::class.java).sortedBy { it.id }) {
            val elkNode = skeleton.nodeMap[group.id] ?: continue
            shapes[group.id] = Rect(artifactStartX, groupOffsetY, elkNode.width, elkNode.height)
            groupOffsetY += elkNode.height + ARTIFACT_MARGIN
        }
    }

    // ── Named rule 7: baseline snap ───────────────────────────────────────────

    /**
     * Identifies the primary flow (the sequence of shapes at the modal centre Y)
     * and snaps all their centre Ys to a single baseline so straight flows render
     * as a horizontal line (not a staircase).
     *
     * Only adjusts shapes that are already close to the modal Y (within tolerance),
     * to avoid pulling subprocess children or boundary handlers onto the primary line.
     */
    private fun snapBaseline(
        model: BpmnModelInstance,
        shapes: MutableMap<String, Rect>,
    ) {
        // Collect non-boundary, non-artifact flow-node shapes
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }
        val artifactIds = (
            model.getModelElementsByType(TextAnnotation::class.java).map { it.id } +
                model.getModelElementsByType(Group::class.java).map { it.id }
            ).toSet()
        val subprocessIds = model.getModelElementsByType(SubProcess::class.java)
            .mapTo(mutableSetOf()) { it.id }

        val primaryCandidates = model.getModelElementsByType(FlowNode::class.java)
            .filter { it.id !in boundaryIds && it.id !in artifactIds && it.id !in subprocessIds }
            .mapNotNull { fn ->
                val s = shapes[fn.id] ?: return@mapNotNull null
                fn.id to (s.y + s.h / 2.0) // centre Y
            }

        if (primaryCandidates.isEmpty()) return

        // Modal centre Y = the one most candidates cluster around
        val centreYs = primaryCandidates.map { it.second }
        val baseline = centreYs.groupBy { roundToGrid(it, BASELINE_BUCKET) }
            .maxByOrNull { it.value.size }
            ?.value?.average() ?: return

        // Snap shapes whose centre Y is within tolerance of the baseline
        primaryCandidates
            .filter { (_, centreY) -> abs(centreY - baseline) <= BASELINE_TOLERANCE }
            .forEach { (id, _) ->
                val s = shapes[id] ?: return@forEach
                val newY = baseline - s.h / 2.0
                if (abs(newY - s.y) >= BASELINE_SNAP_EPSILON) shapes[id] = s.copy(y = newY)
            }
    }

    // ── Named rule 8: association edges from shape centres ────────────────────

    /**
     * Places association edges as straight lines between shape centres,
     * matching the 557-2 behaviour preserved for this stage.
     */
    private fun placeAssociationEdges(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
    ) {
        model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Association::class.java)
            .sortedBy { it.id }
            .forEach { assoc ->
                val sourceRect = shapes[assoc.source?.id] ?: return@forEach
                val targetRect = shapes[assoc.target?.id] ?: return@forEach
                edges[assoc.id] = listOf(
                    Point(sourceRect.x + sourceRect.w / 2.0, sourceRect.y + sourceRect.h / 2.0),
                    Point(targetRect.x + targetRect.w / 2.0, targetRect.y + targetRect.h / 2.0),
                )
            }
    }

    // ── Shared coordinate helper ──────────────────────────────────────────────

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

    // ── Baseline snap helpers ─────────────────────────────────────────────────

    private fun roundToGrid(v: Double, bucket: Double): Long = (v / bucket).toLong()

    // ── Constants ─────────────────────────────────────────────────────────────

    private const val ARTIFACT_MARGIN = 30.0
    private const val BASELINE_BUCKET = 5.0
    private const val BASELINE_TOLERANCE = 15.0
}
