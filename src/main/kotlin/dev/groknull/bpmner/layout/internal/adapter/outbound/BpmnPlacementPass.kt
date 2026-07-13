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
 * - [BpmnEdgeRouter] — reconciles the ELK-routed exception edge's first waypoint to the placed
 *   boundary shape, then routes all sequence-flow / association edges into waypoint polylines.
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

    /** Vertical gap between an edge label's bottom and the edge it sits above. */
    internal const val EDGE_LABEL_GAP_ABOVE = 4.0

    /** Half of EVENT_SIZE — used for boundary straddle offset. */
    private const val BOUNDARY_HALF = BpmnToElkMapper.EVENT_SIZE / 2.0

    /** Minimum horizontal gap between adjacent boundary-event shapes on the same host edge. */
    private const val BOUNDARY_MIN_GAP = 8.0

    /** Sub-pixel threshold below which a position change is treated as no-op. */
    internal const val POSITION_EPSILON = 0.5

    /** Minimum snap delta below which baseline adjustment is skipped (sub-pixel). */
    private const val BASELINE_SNAP_EPSILON = 0.01

    // ── Entry point ───────────────────────────────────────────────────────────

    fun place(model: BpmnModelInstance, skeleton: ElkSkeleton): PlacedLayout {
        val shapes = mutableMapOf<String, Rect>()
        val labels = mutableMapOf<String, Rect>()
        val edges = mutableMapOf<String, List<Point>>()
        val expanded = mutableSetOf<String>()

        placeNodeShapes(model, skeleton, shapes, expanded)
        val straddle = straddleSubprocessEnds(model, shapes)
        // AD-557-10: exception handlers must not sit on the primary baseline. Push each boundary's
        // exclusive exception subgraph below the main flow BEFORE routing, so its edges route down.
        val exceptionNodes = pushExceptionHandlersBelow(model, shapes)
        placeBoundaryShapes(model, skeleton, shapes)
        // Snap the primary flow to one baseline BEFORE routing edges, so co-row shapes share an
        // exact centre Y and straight-line straightening can recognise a clear horizontal corridor.
        snapBaseline(model, shapes, exceptionNodes)
        BpmnEdgeRouter.route(
            model,
            skeleton,
            shapes,
            BpmnEdgeRouter.RoutingHints(exceptionNodes, straddle),
            edges,
        )
        placeLabels(model, shapes, edges, labels)
        placeArtifacts(model, skeleton, shapes)
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
    ): StraddleResult {
        val moved = mutableSetOf<String>()
        val subprocessEnd = mutableMapOf<String, String>()
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
                        subprocessEnd[sub.id] = end.id
                    }
                }
        }
        return StraddleResult(moved, subprocessEnd)
    }

    /**
     * @param movedEnds        end events relocated onto their container's right border.
     * @param subprocessEnd    subprocess id -> the straddling end event on its right border.
     */
    internal data class StraddleResult(
        val movedEnds: Set<String>,
        val subprocessEnd: Map<String, String>,
    )

    // ── Named rule 1c: exception handlers below the primary baseline ──────────

    /**
     * AD-557-10: "the placement pass ensures [the handler] is not on the primary baseline."
     *
     * Identifies each boundary event's exclusive exception subgraph — nodes reachable from the
     * boundary that are NOT reachable from a start event without passing through a boundary — and
     * shifts those shapes down so they sit below the main flow. This keeps error/exception paths
     * off the happy-path line and lets their edges route downward (never up through the host).
     *
     * A node shared with the main flow (e.g. a rejoin target reachable from the start) is left in
     * place; only nodes exclusive to the exception path are moved.
     *
     * Returns the set of moved (exception) node IDs so later passes can treat them specially.
     */
    private fun pushExceptionHandlersBelow(
        model: BpmnModelInstance,
        shapes: MutableMap<String, Rect>,
    ): Set<String> {
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }
        if (boundaryIds.isEmpty()) return emptySet()

        val successors = buildSuccessorMap(model)
        val mainFlow = reachableMainFlow(model, successors, boundaryIds)
        val exceptionNodes = reachableExceptionNodes(successors, boundaryIds, mainFlow)
        if (exceptionNodes.isEmpty()) return emptySet()

        shiftExceptionNodesBelow(shapes, mainFlow, exceptionNodes)
        return exceptionNodes
    }

    /** Sequence-flow adjacency: source id -> list of target ids. */
    private fun buildSuccessorMap(model: BpmnModelInstance): Map<String, List<String>> {
        val successors = mutableMapOf<String, MutableList<String>>()
        model.getModelElementsByType(SequenceFlow::class.java).forEach { sf ->
            val s = sf.source?.id ?: return@forEach
            val t = sf.target?.id ?: return@forEach
            successors.getOrPut(s) { mutableListOf() }.add(t)
        }
        return successors
    }

    /**
     * Breadth-first reachable set from [seeds] over [successors]. A node is visited if [accept]
     * returns true for it; only accepted nodes are expanded further. Returns all accepted nodes.
     */
    private fun reachable(
        seeds: Collection<String>,
        successors: Map<String, List<String>>,
        accept: (String) -> Boolean,
    ): Set<String> {
        val visited = mutableSetOf<String>()
        val stack = ArrayDeque(seeds)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (id in visited || !accept(id)) continue
            visited.add(id)
            stack.addAll(successors[id].orEmpty())
        }
        return visited
    }

    /** Nodes reachable from any start event without traversing a boundary event. */
    private fun reachableMainFlow(
        model: BpmnModelInstance,
        successors: Map<String, List<String>>,
        boundaryIds: Set<String>,
    ): Set<String> {
        val starts = model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.StartEvent::class.java)
            .map { it.id }
        return reachable(starts, successors) { it !in boundaryIds }
    }

    /** Nodes reachable from a boundary event that are not part of the main flow. */
    private fun reachableExceptionNodes(
        successors: Map<String, List<String>>,
        boundaryIds: Set<String>,
        mainFlow: Set<String>,
    ): Set<String> {
        val seeds = boundaryIds.flatMap { successors[it].orEmpty() }
        return reachable(seeds, successors) { it !in mainFlow && it !in boundaryIds }
    }

    /** Shifts all exception-node shapes down so their top clears the main flow's bottom. */
    private fun shiftExceptionNodesBelow(
        shapes: MutableMap<String, Rect>,
        mainFlow: Set<String>,
        exceptionNodes: Set<String>,
    ) {
        val mainBottom = shapes.filterKeys { it in mainFlow }.values.maxOfOrNull { it.y + it.h } ?: return
        val exTop = shapes.filterKeys { it in exceptionNodes }.values.minOfOrNull { it.y } ?: return
        val shift = (mainBottom + EXCEPTION_LANE_GAP) - exTop
        if (shift > 0.0) {
            exceptionNodes.forEach { id ->
                shapes[id]?.let { r -> shapes[id] = r.copy(y = r.y + shift) }
            }
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
                    mid.x - LABEL_WIDTH / 2.0,
                    mid.y - LABEL_HEIGHT - EDGE_LABEL_GAP_ABOVE,
                    LABEL_WIDTH,
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
        exceptionNodes: Set<String>,
    ) {
        // Collect non-boundary, non-artifact, non-exception flow-node shapes. Exception-lane
        // nodes were deliberately pushed below the baseline and must not be snapped back up.
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }
        val artifactIds = (
            model.getModelElementsByType(TextAnnotation::class.java).map { it.id } +
                model.getModelElementsByType(Group::class.java).map { it.id }
            ).toSet()
        val subprocessIds = model.getModelElementsByType(SubProcess::class.java)
            .mapTo(mutableSetOf()) { it.id }

        val primaryCandidates = model.getModelElementsByType(FlowNode::class.java)
            .filter {
                it.id !in boundaryIds &&
                    it.id !in artifactIds &&
                    it.id !in subprocessIds &&
                    it.id !in exceptionNodes
            }
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

    /** Vertical gap between the main flow's bottom and the top of the exception lane. */
    private const val EXCEPTION_LANE_GAP = 40.0

    /** Padding between a group box and the flow bounding box it encloses. */
    private const val GROUP_PADDING = 25.0

    /** Extra padding per additional group so multiple groups nest concentrically, not overlap. */
    private const val GROUP_NEST_STEP = 15.0
}
