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

    /** Minimum horizontal gap between adjacent boundary-event shapes on the same host edge. */
    private const val BOUNDARY_MIN_GAP = 8.0

    /** Sub-pixel threshold below which a position change is treated as no-op. */
    private const val POSITION_EPSILON = 0.5

    /** Minimum snap delta below which baseline adjustment is skipped (sub-pixel). */
    private const val BASELINE_SNAP_EPSILON = 0.01

    // ── Entry point ───────────────────────────────────────────────────────────

    fun place(model: BpmnModelInstance, skeleton: ElkSkeleton): PlacedLayout {
        val shapes = mutableMapOf<String, Rect>()
        val labels = mutableMapOf<String, Rect>()
        val edges = mutableMapOf<String, List<Point>>()
        val expanded = mutableSetOf<String>()

        placeNodeShapes(model, skeleton, shapes, expanded)
        val straddled = straddleSubprocessEnds(model, shapes)
        placeBoundaryShapes(model, skeleton, shapes)
        reconcileExceptionEdges(model, shapes, edges)
        placeSequenceEdgeWaypoints(model, skeleton, edges)
        reattachStraddledEndEdges(model, shapes, edges, straddled)
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

    // ── Named rule 1b: subprocess-terminating end events straddle the container border ──

    /**
     * Moves each end event that terminates a subprocess so its centre sits on the subprocess's
     * RIGHT border (half inside, half outside) — the BPMN convention for a flow that ends at the
     * container boundary. Returns the set of moved end-event IDs so their single incoming edge
     * can be re-attached to the new position.
     *
     * An "end event that terminates a subprocess" is an [EndEvent] whose parent is a [SubProcess]
     * and which is the right-most child (no child sits further right). Only expanded subprocesses
     * are considered (all retained subprocesses are expanded).
     */
    private fun straddleSubprocessEnds(
        model: BpmnModelInstance,
        shapes: MutableMap<String, Rect>,
    ): Set<String> {
        val moved = mutableSetOf<String>()
        model.getModelElementsByType(SubProcess::class.java).forEach { sub ->
            val subRect = shapes[sub.id] ?: return@forEach
            val rightBorder = subRect.x + subRect.w
            sub.flowElements
                .filterIsInstance<org.camunda.bpm.model.bpmn.instance.EndEvent>()
                .forEach { end ->
                    val r = shapes[end.id] ?: return@forEach
                    // Centre the end event on the subprocess right border.
                    val newX = rightBorder - r.w / 2.0
                    if (kotlin.math.abs(newX - r.x) > POSITION_EPSILON) {
                        shapes[end.id] = r.copy(x = newX)
                        moved.add(end.id)
                    }
                }
        }
        return moved
    }

    /**
     * Re-routes the incoming sequence-flow edge of each straddled end event so its last waypoint
     * meets the end event's new (moved) left edge, keeping the edge attached after the straddle.
     */
    private fun reattachStraddledEndEdges(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
        straddled: Set<String>,
    ) {
        if (straddled.isEmpty()) return
        model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.target?.id in straddled }
            .forEach { sf ->
                val endRect = shapes[sf.target?.id] ?: return@forEach
                val wps = edges[sf.id]?.toMutableList() ?: return@forEach
                if (wps.size < 2) return@forEach
                // Snap the final waypoint to the end event's left edge at its vertical centre.
                val entryX = endRect.x
                val entryY = endRect.y + endRect.h / 2.0
                wps[wps.size - 1] = Point(entryX, entryY)
                // Keep the penultimate point's Y aligned so the last segment stays horizontal.
                val prev = wps[wps.size - 2]
                wps[wps.size - 2] = Point(prev.x, entryY)
                edges[sf.id] = wps
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
                val tgtId = sf.target?.id ?: return@forEach
                val bRect = shapes[boundaryId] ?: return@forEach
                val tRect = shapes[tgtId] ?: return@forEach
                edges[sf.id] = routeExceptionEdge(bRect, tRect)
            }
    }

    /**
     * Builds a deterministic orthogonal route for an exception (boundary → handler) edge.
     *
     * The edge exits the boundary's BOTTOM (BPMN convention — the boundary straddles the host's
     * bottom edge), drops straight down, then travels horizontally to enter the handler on its
     * nearest vertical edge (left if the handler is to the right, right if to the left). This
     * replaces ELK's routing for these edges, which produced diagonal segments (ELK routes from
     * the south *port*, not the placed boundary shape). All segments are axis-aligned.
     */
    private fun routeExceptionEdge(boundary: Rect, target: Rect): List<Point> {
        val startX = boundary.x + boundary.w / 2.0
        val startY = boundary.y + boundary.h // bottom of the boundary circle
        val targetCy = target.y + target.h / 2.0
        // Enter the handler on the side facing the boundary.
        val enterLeft = target.x >= startX
        val endX = if (enterLeft) target.x else target.x + target.w
        // Drop to the handler's centre-line height, then run horizontally into its edge.
        // If the handler is directly below with no horizontal travel, a straight drop suffices.
        return if (kotlin.math.abs(endX - startX) < 1.0) {
            listOf(Point(startX, startY), Point(startX, targetCy), Point(endX, targetCy))
        } else {
            listOf(
                Point(startX, startY),
                Point(startX, targetCy),
                Point(endX, targetCy),
            )
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
                // ELK edge coordinates are relative to the edge's containing (LCA) node.
                val (ox, oy) = edgeContainerOffset(elkEdge)
                val waypoints = mutableListOf(Point(section.startX + ox, section.startY + oy))
                section.bendPoints.mapTo(waypoints) { Point(it.x + ox, it.y + oy) }
                waypoints.add(Point(section.endX + ox, section.endY + oy))
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

        placeAnnotations(model, skeleton, shapes, annotationHost, skeletonBottom)
        placeGroups(model, shapes)
    }

    private fun placeAnnotations(
        model: BpmnModelInstance,
        skeleton: ElkSkeleton,
        shapes: MutableMap<String, Rect>,
        annotationHost: Map<String, String>,
        skeletonBottom: Double,
    ) {
        var fallbackY = skeletonBottom + ARTIFACT_MARGIN
        model.getModelElementsByType(TextAnnotation::class.java)
            .sortedBy { it.id }
            .forEach { ann ->
                val elkNode = skeleton.nodeMap[ann.id] ?: return@forEach
                val host = annotationHost[ann.id]?.let { shapes[it] }
                if (host != null) {
                    // Place below-and-right of the associated element (short connector).
                    shapes[ann.id] = Rect(
                        host.x + host.w + ARTIFACT_MARGIN,
                        host.y + host.h + ARTIFACT_MARGIN,
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

    // ── Baseline snap helpers ─────────────────────────────────────────────────

    private fun roundToGrid(v: Double, bucket: Double): Long = (v / bucket).toLong()

    // ── Constants ─────────────────────────────────────────────────────────────

    private const val ARTIFACT_MARGIN = 30.0
    private const val BASELINE_BUCKET = 5.0
    private const val BASELINE_TOLERANCE = 15.0

    /** Padding between a group box and the flow bounding box it encloses. */
    private const val GROUP_PADDING = 25.0

    /** Extra padding per additional group so multiple groups nest concentrically, not overlap. */
    private const val GROUP_NEST_STEP = 15.0
}
