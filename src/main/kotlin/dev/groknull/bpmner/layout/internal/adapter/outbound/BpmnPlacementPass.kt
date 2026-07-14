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
import org.camunda.bpm.model.xml.instance.ModelElementInstance
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
 * - [alignHandlerComponentsX] — shifts handler component nodes rightward so they do not appear
 *   visually "behind" (to the left of) their host. ELK places disconnected components
 *   independently from x=0; this rule ensures handlers start at or after the host's right edge.
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
        // Phase-2 rule 1b: align handler component X so handlers appear to the right of their host.
        // ELK places disconnected components starting from x=0 independently; without this rule
        // handler tasks land to the left of their host task, which renders as a backwards flow.
        // Returns a map of nodeId→xShift so sequence-flow waypoints can be corrected too.
        val handlerXShifts = alignHandlerComponentsX(model, shapes)
        // Phase-2 rule 1c: straddle subprocess-terminating end events on the container right border.
        // BPMN convention: a flow that terminates a subprocess has its end event centred on the
        // subprocess's right edge (half inside, half outside). Returns the set of moved end IDs
        // so Flow_from_sub can be re-anchored to the straddling end event's right edge.
        val straddledEnds = straddleSubprocessEnds(model, shapes)
        // Phase-2 rule 2: boundary shapes on host bottom edge (decoration only).
        placeBoundaryShapes(model, skeleton, shapes)
        // Phase-2 rule 3: exception edges — the ONE sanctioned bespoke edge op (AD-557-12).
        routeExceptionEdges(model, shapes, edges)
        // Phase-2 rule 4: ordinary sequence-flow waypoints copied verbatim from ELK sections,
        // then corrected for any handler component X-shift applied in rule 1b.
        BpmnEdgeRouter.placeSequenceEdgeWaypoints(model, skeleton, edges)
        if (handlerXShifts.isNotEmpty()) shiftHandlerEdgeWaypoints(model, shapes, edges, handlerXShifts)
        if (straddledEnds.isNotEmpty()) reanchorSubprocessExitFlows(model, shapes, edges, straddledEnds)
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

    // ── Named rule 1b: handler component X alignment ─────────────────────────

    /**
     * Shifts handler-component nodes rightward so they appear to the right of their host,
     * not behind it.
     *
     * ELK places each disconnected component starting from x=0 in its own coordinate space.
     * When the handler component's leftmost node lands to the LEFT of the host task's left
     * edge, it renders as a "backwards" flow in bpmn-js. This named rule corrects the X
     * alignment by shifting the entire handler component by the delta needed to place its
     * leftmost node at `hostRight + HANDLER_COMPONENT_X_GAP`.
     *
     * This is a decoration correction analogous to [placeBoundaryShapes]: the handler node's
     * absolute X has no BPMN-semantic meaning assigned by ELK (it is an artefact of component
     * independence), so phase 2 owns it. The Y position (the vertical band) remains exactly
     * where ELK placed it — only X is adjusted.
     */

    /** Returns a map of nodeId → xShift for every handler node that was shifted. */
    @Suppress("CyclomaticComplexMethod")
    private fun alignHandlerComponentsX(
        model: BpmnModelInstance,
        shapes: MutableMap<String, Rect>,
    ): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }
        if (boundaryIds.isEmpty()) return result

        val successors = buildFlowSuccessors(model)
        val mainFlow = reachableFrom(
            seeds = model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.StartEvent::class.java)
                .map { it.id },
            successors = successors,
            exclude = boundaryIds,
        )
        val handlerSeeds = boundaryIds.flatMap { successors[it].orEmpty() }
        if (handlerSeeds.isEmpty()) return result

        model.getModelElementsByType(BoundaryEvent::class.java).forEach { be ->
            shiftHandlerComponentForBoundary(be, successors, mainFlow, boundaryIds, shapes, result)
        }
        return result
    }

    /** Builds a sequence-flow successor map: sourceId → list of targetIds. */
    private fun buildFlowSuccessors(model: BpmnModelInstance): Map<String, List<String>> {
        val map = mutableMapOf<String, MutableList<String>>()
        model.getModelElementsByType(SequenceFlow::class.java).forEach { sf ->
            val s = sf.source?.id ?: return@forEach
            val t = sf.target?.id ?: return@forEach
            map.getOrPut(s) { mutableListOf() }.add(t)
        }
        return map
    }

    /**
     * BFS from [seeds] over [successors], excluding nodes in [exclude].
     * Returns all reachable node IDs (excluding seeds not passing the exclude check).
     */
    private fun reachableFrom(
        seeds: Collection<String>,
        successors: Map<String, List<String>>,
        exclude: Set<String>,
    ): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(seeds)
        while (queue.isNotEmpty()) {
            val id = queue.removeLast()
            if (id in visited || id in exclude) continue
            visited.add(id)
            queue.addAll(successors[id].orEmpty())
        }
        return visited
    }

    /** Shifts the handler component for a single boundary event if it is left of the host. */
    @Suppress("LongParameterList")
    private fun shiftHandlerComponentForBoundary(
        be: BoundaryEvent,
        successors: Map<String, List<String>>,
        mainFlow: Set<String>,
        boundaryIds: Set<String>,
        shapes: MutableMap<String, Rect>,
        result: MutableMap<String, Double>,
    ) {
        val hostId = be.attachedTo?.id ?: return
        val hostRight = (shapes[hostId] ?: return).let { it.x + it.w }
        val thisHandlers = reachableFrom(
            seeds = successors[be.id].orEmpty(),
            successors = successors,
            exclude = mainFlow + boundaryIds,
        )
        if (thisHandlers.isEmpty()) return
        val handlerLeft = thisHandlers.mapNotNull { shapes[it]?.x }.minOrNull() ?: return
        val shift = (hostRight + HANDLER_COMPONENT_X_GAP) - handlerLeft
        if (shift <= POSITION_EPSILON) return
        thisHandlers.forEach { id ->
            shapes[id]?.let { r ->
                shapes[id] = r.copy(x = r.x + shift)
                result[id] = shift
            }
        }
    }

    /**
     * Corrects ELK-section waypoints for flows whose source or target was shifted by
     * [alignHandlerComponentsX]. Non-boundary-source flows only; exception edges are already correct.
     */
    private fun shiftHandlerEdgeWaypoints(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
        handlerXShifts: Map<String, Double>,
    ) {
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }
        model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.source?.id !in boundaryIds }
            .forEach { sf ->
                val srcId = sf.source?.id
                val tgtId = sf.target?.id
                val srcShift = handlerXShifts[srcId]
                val tgtShift = handlerXShifts[tgtId]
                val shift = srcShift ?: tgtShift ?: return@forEach
                if (edges[sf.id] == null) return@forEach
                when {
                    // Rejoin (handler→main): recompute exit-top-rise-across route.
                    srcShift != null && tgtShift == null ->
                        routeRejoinEdge(srcId, tgtId, shapes, edges, sf.id)
                    // Forward (main→handler): recompute right-to-left border route.
                    tgtShift != null && srcShift == null ->
                        routeForwardToHandlerEdge(srcId, tgtId, shapes, edges, sf.id)
                    // Both shifted: uniform shift.
                    else -> edges[sf.id] = edges[sf.id]!!.map { it.copy(x = it.x + shift) }
                }
            }
    }

    private fun routeRejoinEdge(
        srcId: String?,
        tgtId: String?,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
        edgeId: String,
    ) {
        val srcRect = shapes[srcId] ?: return
        val tgtRect = shapes[tgtId] ?: return
        val srcCx = srcRect.x + srcRect.w / 2.0
        val srcTop = srcRect.y
        val tgtCy = tgtRect.y + tgtRect.h / 2.0
        val enterX = if (srcCx <= tgtRect.x) tgtRect.x else tgtRect.x + tgtRect.w
        edges[edgeId] = listOf(Point(srcCx, srcTop), Point(srcCx, tgtCy), Point(enterX, tgtCy))
    }

    private fun routeForwardToHandlerEdge(
        srcId: String?,
        tgtId: String?,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
        edgeId: String,
    ) {
        val srcRect = shapes[srcId] ?: return
        val tgtRect = shapes[tgtId] ?: return
        val srcRight = srcRect.x + srcRect.w
        val srcCy = srcRect.y + srcRect.h / 2.0
        val tgtLeft = tgtRect.x
        val tgtCy = tgtRect.y + tgtRect.h / 2.0
        edges[edgeId] = listOf(Point(srcRight, srcCy), Point(tgtLeft, tgtCy))
    }

    // ── Named rule 1c: subprocess-terminating end events straddle the right border ──

    /**
     * Moves each subprocess-terminating end event so its centre sits on the subprocess's RIGHT
     * border (half inside, half outside) — the BPMN convention for a flow that ends at the
     * container boundary. Returns the set of moved end-event IDs.
     */
    private fun straddleSubprocessEnds(
        model: BpmnModelInstance,
        shapes: MutableMap<String, Rect>,
    ): Set<String> {
        val moved = mutableSetOf<String>()
        model.getModelElementsByType(SubProcess::class.java).forEach { sub ->
            val subRect = shapes[sub.id] ?: return@forEach
            val rightBorder = subRect.x + subRect.w
            val subCentreY = subRect.y + subRect.h / 2.0
            sub.flowElements.filterIsInstance<org.camunda.bpm.model.bpmn.instance.EndEvent>()
                .forEach { end ->
                    val r = shapes[end.id] ?: return@forEach
                    val newX = rightBorder - r.w / 2.0
                    val newY = subCentreY - r.h / 2.0
                    shapes[end.id] = r.copy(x = newX, y = newY)
                    moved.add(end.id)
                }
        }
        return moved
    }

    /**
     * Re-anchors edges affected by [straddleSubprocessEnds]:
     * 1. Intra-subprocess edges whose target is the straddled end event — snap their last
     *    waypoint to the end event's new (straddled) position.
     * 2. Exit edges whose source is the subprocess — re-route from the straddled end's right
     *    edge to the outer target's left edge.
     */
    private fun reanchorSubprocessExitFlows(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
        straddledEnds: Set<String>,
    ) {
        model.getModelElementsByType(SequenceFlow::class.java).forEach { sf ->
            val srcId = sf.source?.id ?: return@forEach
            val tgtId = sf.target?.id ?: return@forEach
            // Case 1: intra-subprocess edge whose TARGET is the straddled end event
            if (tgtId in straddledEnds) {
                val endRect = shapes[tgtId] ?: return@forEach
                val wps = edges[sf.id] ?: return@forEach
                if (wps.size >= 2) {
                    val entryCx = endRect.x + endRect.w / 2.0
                    val entryCy = endRect.y + endRect.h / 2.0
                    edges[sf.id] = wps.dropLast(1) + Point(entryCx, entryCy)
                }
                return@forEach
            }
            // Case 2: exit edge whose SOURCE is the subprocess
            val srcElement = model.getModelElementById<ModelElementInstance>(srcId)
            if (srcElement !is SubProcess) return@forEach
            val endId = srcElement.flowElements.filterIsInstance<org.camunda.bpm.model.bpmn.instance.EndEvent>()
                .firstOrNull { it.id in straddledEnds }?.id ?: return@forEach
            val endRect = shapes[endId] ?: return@forEach
            val tgtRect = shapes[tgtId] ?: return@forEach
            edges[sf.id] = listOf(
                Point(endRect.x + endRect.w, endRect.y + endRect.h / 2.0),
                Point(tgtRect.x, tgtRect.y + tgtRect.h / 2.0),
            )
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
                if (startX >= tRect.x && startX <= tRect.x + tRect.w) {
                    val endY = if (tRect.y >= startY) tRect.y else tRect.y + tRect.h
                    edges[sf.id] = listOf(
                        Point(startX, startY),
                        Point(startX, endY),
                    )
                } else {
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
        placeNodeLabels(model, shapes, labels)
        placeSequenceFlowLabels(model, edges, labels)
    }

    private fun placeNodeLabels(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        labels: MutableMap<String, Rect>,
    ) {
        model.getModelElementsByType(FlowNode::class.java)
            .filter { !it.name.isNullOrBlank() }
            .sortedBy { it.id }
            .forEach { flowNode ->
                val shape = shapes[flowNode.id] ?: return@forEach
                val name = flowNode.name ?: ""
                val (labelWidth, labelHeight) = estimateLabelDimensions(name, LABEL_WIDTH)
                val labelX = shape.x + shape.w / 2.0 - labelWidth / 2.0
                val labelY = shape.y + shape.h + LABEL_GAP_BELOW
                labels[flowNode.id] = Rect(labelX, labelY, labelWidth, labelHeight)
            }
    }

    private fun placeSequenceFlowLabels(
        model: BpmnModelInstance,
        edges: Map<String, List<Point>>,
        labels: MutableMap<String, Rect>,
    ) {
        model.getModelElementsByType(SequenceFlow::class.java)
            .filter { !it.name.isNullOrBlank() }
            .sortedBy { it.id }
            .forEach { sf ->
                val wps = edges[sf.id]?.takeIf { it.size >= 2 } ?: return@forEach
                val name = sf.name ?: ""
                val (labelWidth, labelHeight) = estimateLabelDimensions(name, EDGE_LABEL_WIDTH)

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
                    labels[sf.id] = Rect(
                        labelX,
                        labelY,
                        labelWidth,
                        labelHeight,
                    )
                } else {
                    placeNonHorizontalEdgeLabel(sf.id, wps, name, labels)
                }
            }
    }

    private fun placeNonHorizontalEdgeLabel(
        id: String,
        wps: List<Point>,
        name: String,
        labels: MutableMap<String, Rect>,
    ) {
        val (labelWidth, labelHeight) = estimateLabelDimensions(name, EDGE_LABEL_WIDTH)
        val isBackward = wps.first().x > wps.last().x
        val mid = BpmnEdgeRouter.polylineMidpoint(wps)
        if (isVerticalSegment(wps, mid)) {
            labels[id] = Rect(
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
            labels[id] = Rect(
                mid.x - labelWidth / 2.0,
                labelY,
                labelWidth,
                labelHeight,
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

    data class HorizontalSegment(val mid: Point, val startX: Double, val endX: Double)

    private fun findLongestHorizontalSegment(wps: List<Point>): HorizontalSegment? {
        var longestLen = -1.0
        var bestSeg: HorizontalSegment? = null
        for (i in 1 until wps.size) {
            val p1 = wps[i - 1]
            val p2 = wps[i]
            if (kotlin.math.abs(p1.y - p2.y) < EPSILON) { // Horizontal
                val len = kotlin.math.abs(p2.x - p1.x)
                if (len > longestLen) {
                    longestLen = len
                    bestSeg = HorizontalSegment(Point((p1.x + p2.x) / 2.0, p1.y), p1.x, p2.x)
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

    private fun estimateLabelDimensions(name: String, maxWidth: Double): Pair<Double, Double> {
        val words = name.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Pair(0.0, 0.0)

        // Find longest word width
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

    // ── Constants ─────────────────────────────────────────────────────────────

    private const val ARTIFACT_MARGIN = 30.0

    /**
     * Minimum horizontal gap between a host's right edge and the handler component's left edge
     * when [alignHandlerComponentsX] shifts the handler component rightward.
     * Matches NODE_NODE_BETWEEN_LAYERS so the handler appears in the next visual column.
     */
    private const val HANDLER_COMPONENT_X_GAP = 30.0

    /** Padding between a group box and the flow bounding box it encloses. */
    private const val GROUP_PADDING = 25.0

    /** Extra padding per additional group so multiple groups nest concentrically, not overlap. */
    private const val GROUP_NEST_STEP = 15.0

    private const val LABEL_MARGIN = 8.0
    private const val EPSILON = 0.001
    private const val CHAR_WIDTH = 6.5
    private const val SPACE_WIDTH = 4.0
    private const val LINE_HEIGHT = 14.0
}
