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

/**
 * Phase 2 — BPMN placement pass: deterministic, BPMN-aware decoration.
 *
 * Reads the ELK skeleton's coordinates as fixed/immutable input and applies each
 * BPMN placement convention as a NAMED RULE in one place. Per AD-557-11 this pass
 * NEVER relocates a node ELK placed — every "put a node here" convention is a phase-1
 * ELK constraint, not a post-ELK coordinate patch.
 *
 * - [placeNodeShapes] — all non-boundary flow nodes at their absolute ELK coordinates (immutable).
 * - [placeBoundaryShapes] — boundary-event shapes on the host's BOTTOM edge, straddling,
 *   evenly distributed for multiple attachments.
 * - [routeExceptionEdges] — the ONE sanctioned bespoke edge op (AD-557-12): three-point
 *   orthogonal polyline from each placed boundary shape to its handler.
 * - [BpmnEdgeRouter.placeSequenceEdgeWaypoints] — copies ELK section waypoints verbatim.
 * - [placeLabels] — fixed 90×20 box below nodes / at edge-waypoint mid.
 * - [placeArtifacts] — text annotations and groups as sidecar geometry.
 * - [BpmnEdgeRouter.placeAssociationEdges] — association connectors between shape borders.
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

    /** Width of sequence-flow edge labels, widened to prevent text wrapping/splitting. */
    internal const val EDGE_LABEL_WIDTH = 120.0

    /** bpmn-js DEFAULT_LABEL_SIZE height. */
    internal const val LABEL_HEIGHT = 20.0

    /** Vertical gap between a node's bottom edge and the top of its external label. */
    internal const val LABEL_GAP_BELOW = 2.0

    /** Vertical gap between an edge label's bottom and the edge it sits above. */
    internal const val EDGE_LABEL_GAP_ABOVE = 4.0

    /** Half of EVENT_SIZE — used for boundary straddle offset. */
    private const val BOUNDARY_HALF = BpmnToElkMapper.EVENT_SIZE / 2.0

    /** Minimum horizontal gap between adjacent boundary-event shapes on the same host edge. */
    private const val BOUNDARY_MIN_GAP = 8.0

    /** Sub-pixel threshold below which a position change is treated as no-op. */
    internal const val POSITION_EPSILON = 0.5

    // ── Entry point ───────────────────────────────────────────────────────────

    fun place(model: BpmnModelInstance, skeleton: ElkSkeleton): PlacedLayout {
        val shapes = mutableMapOf<String, Rect>()
        val labels = mutableMapOf<String, Rect>()
        val edges = mutableMapOf<String, List<Point>>()
        val expanded = mutableSetOf<String>()

        // Phase-2 rule 1: copy ELK node bounds verbatim (AD-557-11 — no node relocation).
        placeNodeShapes(model, skeleton, shapes, expanded)
        // Phase-2 rule 2: boundary shapes on host bottom edge (decoration only).
        placeBoundaryShapes(model, skeleton, shapes)
        // Phase-2 rule 3: exception edges — the ONE sanctioned bespoke edge op (AD-557-12).
        routeExceptionEdges(model, shapes, edges)
        // Phase-2 rule 4: ordinary sequence-flow waypoints copied verbatim from ELK sections.
        BpmnEdgeRouter.placeSequenceEdgeWaypoints(model, skeleton, edges)
        // Phase-2 rule 5: labels below nodes / at edge midpoints.
        placeLabels(model, shapes, edges, labels)
        // Phase-2 rule 6: artifact sidecar geometry.
        placeArtifacts(model, skeleton, shapes)
        // Phase-2 rule 7: association connectors between shape borders.
        BpmnEdgeRouter.placeAssociationEdges(model, shapes, edges)

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
     * [BpmnEdgeRouter]. This is the direct fix for BLOCK-557-3 symptom 2.
     *
     * Distribution rule: N boundaries on a host are spaced equidistantly along the
     * host's bottom edge, ordered by boundary event ID for determinism.
     */
    private fun placeBoundaryShapes(
        model: BpmnModelInstance,
        skeleton: ElkSkeleton,
        shapes: MutableMap<String, Rect>,
    ) {
        // Map each boundary to its handler (exception-flow target) for ordering.
        val handlerOf = mutableMapOf<String, String>()
        model.getModelElementsByType(SequenceFlow::class.java).forEach { sf ->
            val src = sf.source?.id
            val tgt = sf.target?.id
            if (src != null && tgt != null) handlerOf[src] = tgt
        }

        // Group boundaries by host ID.
        val byHost = model.getModelElementsByType(BoundaryEvent::class.java)
            .groupBy { it.attachedTo?.id }

        for ((hostId, boundaries) in byHost.filterKeys { it != null }) {
            val hostNode = skeleton.nodeMap[hostId] ?: continue
            val hostRect = shapes[hostId] ?: run {
                val (hx, hy) = absolutePosition(hostNode)
                Rect(hx, hy, hostNode.width, hostNode.height)
            }
            placeBoundariesOnHost(hostRect, boundaries, shapes, handlerOf)
        }
    }

    /**
     * Places N boundary events on the host's bottom edge, straddling, ordered left-to-right by
     * their handler's vertical position so their (drop-then-right) exception edges do not cross,
     * and spaced so the shapes never overlap.
     *
     * Ordering rule: the boundary whose handler sits highest is placed leftmost. Because each
     * exception edge drops straight down and then runs right, nesting the drops by handler height
     * keeps the horizontal runs from crossing another boundary's drop.
     */
    private fun placeBoundariesOnHost(
        hostRect: Rect,
        boundaries: List<BoundaryEvent>,
        shapes: MutableMap<String, Rect>,
        handlerOf: Map<String, String>,
    ) {
        val eventSize = BpmnToElkMapper.EVENT_SIZE
        // Order by handler Y (topmost handler first); fall back to boundary id for determinism.
        val ordered = boundaries.sortedWith(
            compareBy({ be -> shapes[handlerOf[be.id]]?.y ?: Double.MAX_VALUE }, { it.id }),
        )
        val n = ordered.size
        val centreY = hostRect.y + hostRect.h // bottom edge y
        val pitch = eventSize + BOUNDARY_MIN_GAP // centre-to-centre distance guaranteeing no overlap

        // Even distribution would give this centre-to-centre step:
        val evenStep = hostRect.w / (n + 1).toDouble()
        if (evenStep >= pitch) {
            // Even spacing already keeps shapes from overlapping.
            ordered.forEachIndexed { index, be ->
                val centreX = hostRect.x + evenStep * (index + 1)
                shapes[be.id] = Rect(centreX - BOUNDARY_HALF, centreY - BOUNDARY_HALF, eventSize, eventSize)
            }
        } else {
            // Too tight for even spacing: pack at the minimum pitch, centred on the host edge.
            val totalSpan = (n - 1) * pitch
            var centreX = hostRect.x + hostRect.w / 2.0 - totalSpan / 2.0
            ordered.forEach { be ->
                shapes[be.id] = Rect(centreX - BOUNDARY_HALF, centreY - BOUNDARY_HALF, eventSize, eventSize)
                centreX += pitch
            }
        }
    }

    // ── Named rule 3: exception edges (the ONE sanctioned bespoke edge operation) ──

    /**
     * Routes exception edges as deterministic three-point orthogonal polylines.
     *
     * This is the ONE sanctioned bespoke edge operation (AD-557-12). For each boundary event
     * with an outgoing exception flow, produces:
     *   (bCx, bBottom) → (bCx, handlerCy) → (handlerNearEdge, handlerCy)
     *
     * Where:
     * - (bCx, bBottom) is the centre-bottom of the placed boundary shape.
     * - handlerCy is the handler's centre Y.
     * - handlerNearEdge is the handler's left edge if the handler is to the right, right edge
     *   otherwise (so the arrowhead lands on the handler's border facing the boundary).
     *
     * No obstacle detection. The handler is already below the main flow (ELK component stacking);
     * the drop-then-horizontal path will not pass through the main flow.
     */
    private fun routeExceptionEdges(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
    ) {
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }

        model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.source?.id in boundaryIds }
            .sortedBy { it.id }
            .forEach { sf ->
                val bRect = shapes[sf.source?.id ?: return@forEach] ?: return@forEach
                val tRect = shapes[sf.target?.id ?: return@forEach] ?: return@forEach
                val startX = bRect.x + bRect.w / 2.0
                val startY = bRect.y + bRect.h
                val targetCy = tRect.y + tRect.h / 2.0
                val enterLeft = tRect.x >= startX
                val endX = if (enterLeft) tRect.x else tRect.x + tRect.w
                edges[sf.id] = listOf(
                    Point(startX, startY),
                    Point(startX, targetCy),
                    Point(endX, targetCy),
                )
            }
    }

    // ── Named rule 5: labels below nodes / at edge-waypoint mid ───────────────

    /**
     * Places label bounds using the bpmn-js DEFAULT_LABEL_SIZE (90×20) convention:
     * - Node labels: centred below the node shape (x = shape.cx - 45, y = shape.bottom + gap).
     * - Edge labels: centred on the true geometric midpoint of the edge polyline, nudged just
     *   above the edge, so the label sits on the line it describes and never on a node.
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

        // Sequence-flow edge labels centred on the polyline midpoint, just above the edge.
        model.getModelElementsByType(SequenceFlow::class.java)
            .filter { !it.name.isNullOrBlank() }
            .sortedBy { it.id }
            .forEach { sf ->
                val wps = edges[sf.id]?.takeIf { it.size >= 2 } ?: return@forEach
                val mid = BpmnEdgeRouter.polylineMidpoint(wps)
                labels[sf.id] = Rect(
                    mid.x - EDGE_LABEL_WIDTH / 2.0,
                    mid.y - LABEL_HEIGHT - EDGE_LABEL_GAP_ABOVE,
                    EDGE_LABEL_WIDTH,
                    LABEL_HEIGHT,
                )
            }
    }

    // ── Named rule 6: artifacts as sidecar geometry ───────────────────────────

    /**
     * Places text annotations and groups as non-precedence sidecar geometry off the skeleton.
     *
     * - Text annotations are placed near the element they annotate (following the association
     *   source→target), below-and-right of that element, so the connector is short — this is the
     *   BPMN convention. Annotations with no association fall back to below the primary flow.
     * - Groups are placed below the primary flow, spanning left-to-right, laid out in a row so
     *   they never overlap each other or the annotations.
     *
     * Artifacts are deterministically stacked so unrelated artifact shapes never collide.
     */
    private fun placeArtifacts(
        model: BpmnModelInstance,
        skeleton: ElkSkeleton,
        shapes: MutableMap<String, Rect>,
    ) {
        val skeletonBottom = shapes.values.maxOfOrNull { it.y + it.h } ?: 0.0

        // Map each annotation to the element it is associated with (if any).
        val annotationHost = mutableMapOf<String, String>()
        for (assoc in model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Association::class.java)) {
            val src = assoc.source?.id
            val tgt = assoc.target?.id
            // Association may point either way; the annotation is whichever end is a TextAnnotation.
            val annId = listOf(src, tgt).firstOrNull { id -> shapes[id] == null && id != null }
            val hostId = listOf(src, tgt).firstOrNull { it != annId }
            if (annId != null && hostId != null) annotationHost[annId] = hostId
        }

        // Groups first: they enclose the whole flow, so an associated annotation must be placed
        // clear of the group's bottom border (below), not tucked right against it. Compute the
        // group bottom now (used to drop annotations below the enclosing box).
        placeGroups(model, shapes)
        placeAnnotations(model, skeleton, shapes, annotationHost, skeletonBottom)
    }

    private fun placeAnnotations(
        model: BpmnModelInstance,
        skeleton: ElkSkeleton,
        shapes: MutableMap<String, Rect>,
        annotationHost: Map<String, String>,
        skeletonBottom: Double,
    ) {
        val groupBottom = model.getModelElementsByType(Group::class.java)
            .mapNotNull { shapes[it.id] }
            .maxOfOrNull { it.y + it.h }
        var fallbackY = skeletonBottom + ARTIFACT_MARGIN
        model.getModelElementsByType(TextAnnotation::class.java)
            .sortedBy { it.id }
            .forEach { ann ->
                val elkNode = skeleton.nodeMap[ann.id] ?: return@forEach
                val host = annotationHost[ann.id]?.let { shapes[it] }
                if (host != null) {
                    // Place below-and-right of the associated element (short connector). If a group
                    // box encloses the flow, drop below its bottom border so the annotation text
                    // clears the enclosing box instead of crowding it.
                    val belowHost = host.y + host.h + ARTIFACT_MARGIN
                    val belowGroup = groupBottom?.let { it + ARTIFACT_MARGIN } ?: belowHost
                    shapes[ann.id] = Rect(
                        host.x + host.w + ARTIFACT_MARGIN,
                        maxOf(belowHost, belowGroup),
                        elkNode.width,
                        elkNode.height,
                    )
                } else {
                    shapes[ann.id] = Rect(0.0, fallbackY, elkNode.width, elkNode.height)
                    fallbackY += elkNode.height + ARTIFACT_MARGIN
                }
            }
    }

    private fun placeGroups(
        model: BpmnModelInstance,
        shapes: MutableMap<String, Rect>,
    ) {
        val groups = model.getModelElementsByType(Group::class.java).sortedBy { it.id }
        if (groups.isEmpty()) return

        // A BPMN group is a visual box drawn AROUND a region of the diagram — its purpose is to
        // enclose related elements, not to sit empty in a corner. With no per-element membership
        // in the retained profile, we enclose the whole flow: draw each group as a padded box
        // around the bounding box of the flow-node shapes (events, tasks, gateways, subprocesses).
        // Multiple groups nest concentrically with increasing padding so they remain distinct.
        val flowNodeIds = model.getModelElementsByType(FlowNode::class.java)
            .mapTo(mutableSetOf()) { it.id }
        val flowShapes = shapes.filterKeys { it in flowNodeIds }.values
        if (flowShapes.isEmpty()) return

        val minX = flowShapes.minOf { it.x }
        val minY = flowShapes.minOf { it.y }
        val maxX = flowShapes.maxOf { it.x + it.w }
        val maxY = flowShapes.maxOf { it.y + it.h }

        groups.forEachIndexed { index, group ->
            val pad = GROUP_PADDING + index * GROUP_NEST_STEP
            shapes[group.id] = Rect(
                minX - pad,
                minY - pad,
                (maxX - minX) + 2 * pad,
                (maxY - minY) + 2 * pad,
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

    /**
     * ELK reports edge section coordinates relative to the edge's containing node (the
     * lowest common ancestor of its endpoints). For an edge inside a subprocess compound
     * node, that container is offset from the root, so its waypoints must be shifted by the
     * container's absolute position to become canvas-absolute — the edge equivalent of
     * [absolutePosition]. The root graph has a null identifier and contributes no offset.
     */
    internal fun edgeContainerOffset(edge: org.eclipse.elk.graph.ElkEdge): Pair<Double, Double> {
        val container = edge.containingNode ?: return 0.0 to 0.0
        if (container.identifier == null) return 0.0 to 0.0 // root graph
        return absolutePosition(container)
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    private const val ARTIFACT_MARGIN = 30.0

    /** Padding between a group box and the flow bounding box it encloses. */
    private const val GROUP_PADDING = 25.0

    /** Extra padding per additional group so multiple groups nest concentrically, not overlap. */
    private const val GROUP_NEST_STEP = 15.0
}
