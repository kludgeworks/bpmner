/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.ElkSkeleton
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.Gateway
import org.camunda.bpm.model.bpmn.instance.Group
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.SubProcess
import org.camunda.bpm.model.xml.instance.ModelElementInstance

private typealias Rect = BpmnPlacementPass.Rect
private typealias Point = BpmnPlacementPass.Point

/**
 * Phase 2 — BPMN edge-routing pass: deterministic, BPMN-aware edge decoration.
 *
 * Extracted from [BpmnPlacementPass] (the shape-placement owner) so each object owns a single
 * responsibility seam. This object owns every convention that turns placed shapes into edge
 * waypoint polylines: reconciling exception edges to their placed boundary shapes, copying and
 * cleaning ELK sequence-flow sections, straightening clear corridors, re-attaching straddled end
 * events, re-anchoring subprocess exits, entering rejoin targets from below, routing loop-back
 * arcs over the top, and placing association connectors between shape borders.
 *
 * Shape geometry ([Rect], [Point]) and the shared coordinate helpers ([BpmnPlacementPass.absolutePosition],
 * [BpmnPlacementPass.edgeContainerOffset]) remain in [BpmnPlacementPass] and are referenced here.
 *
 * Constants are named (not magic), not configurable (AD-557-10 and ARCHITECTURE.md Non-goals).
 */
@Suppress("TooManyFunctions")
internal object BpmnEdgeRouter {

    // ── Constants (named rules, not magic numbers) ────────────────────────────

    /** Max centre-Y difference for two shapes to count as sharing a horizontal row. */
    private const val SAME_ROW_TOLERANCE = 3.0

    /** Vertical clearance a loop-back arc leaves above the topmost shape it spans. */
    private const val LOOP_ARC_CLEARANCE = 30.0

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Placement-derived routing hints the pass consumes: the exception-lane node IDs and the
     * subprocess-straddle result. Bundled so [route] carries one hints object instead of several
     * loose parameters.
     *
     * @param exceptionNodes node IDs pushed into the exception lane (their edges route down/up).
     * @param straddle        end events relocated onto their container's right border.
     */
    internal data class RoutingHints(
        val exceptionNodes: Set<String>,
        val straddle: BpmnPlacementPass.StraddleResult,
    )

    /**
     * Routes all sequence-flow / exception edges into waypoint polylines, in the deterministic
     * order the placement pass depends on. Called by [BpmnPlacementPass.place] after shapes are
     * placed and before labels are placed. Association edges are routed separately by
     * [placeAssociationEdges] (after artifacts are placed).
     */
    fun route(
        model: BpmnModelInstance,
        skeleton: ElkSkeleton,
        shapes: Map<String, Rect>,
        hints: RoutingHints,
        edges: MutableMap<String, List<Point>>,
    ) {
        reconcileExceptionEdges(model, shapes, edges)
        routeGatewayBranches(model, shapes, edges)
        placeSequenceEdgeWaypoints(model, skeleton, shapes, hints.exceptionNodes, edges)
        straightenClearHorizontalEdges(model, shapes, edges)
        reattachStraddledEndEdges(model, shapes, edges, hints.straddle.movedEnds)
        reanchorSubprocessExits(model, shapes, edges, hints.straddle.subprocessEnd)
        // BPMN convention: edges rising from a below node merge into the target's bottom edge.
        enterRejoinTargetsFromBelow(model, shapes, edges)
        // BPMN convention: a backward (loop) edge routes up and over the top, not along the row.
        routeLoopBackEdges(model, shapes, edges)
    }

    /**
     * A sequence flow leaving a subprocess is routed by ELK from the subprocess box's right edge,
     * but visually a straddling end event ("Done") sits on that edge — so the flow should appear to
     * start from that end event's RIGHT edge, not the container border.
     *
     * Re-route the whole edge from the straddling end event's right edge (at its centre height) to
     * the target's LEFT edge (at the TARGET's centre height). When the two sit on the same row this
     * is a straight horizontal line; when the target is on a different row (e.g. it dropped to a
     * different baseline), route orthogonally so the arrowhead still lands on the middle of the
     * target's left edge rather than in empty space above/below it.
     */
    private fun reanchorSubprocessExits(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
        subprocessEnd: Map<String, String>,
    ) {
        if (subprocessEnd.isEmpty()) return
        model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.source?.id in subprocessEnd.keys }
            .forEach { sf ->
                val endId = subprocessEnd[sf.source?.id] ?: return@forEach
                val endRect = shapes[endId] ?: return@forEach
                val targetRect = shapes[sf.target?.id] ?: return@forEach
                if (edges[sf.id] == null) return@forEach
                val exitX = endRect.x + endRect.w
                val exitY = endRect.y + endRect.h / 2.0
                val entryX = targetRect.x
                val entryY = targetRect.y + targetRect.h / 2.0
                edges[sf.id] = if (kotlin.math.abs(exitY - entryY) < BpmnPlacementPass.POSITION_EPSILON) {
                    listOf(Point(exitX, exitY), Point(entryX, entryY))
                } else {
                    // Different rows: run horizontally to just before the target, then step to the
                    // target's centre height so the arrowhead meets the middle of its left edge.
                    val midX = (exitX + entryX) / 2.0
                    listOf(
                        Point(exitX, exitY),
                        Point(midX, exitY),
                        Point(midX, entryY),
                        Point(entryX, entryY),
                    )
                }
            }
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

    private fun routeGatewayBranches(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
    ) {
        model.getModelElementsByType(SequenceFlow::class.java).forEach { sf ->
            val srcId = sf.source?.id ?: return@forEach
            val tgtId = sf.target?.id ?: return@forEach
            val s = shapes[srcId] ?: return@forEach
            val t = shapes[tgtId] ?: return@forEach
            val srcElement = model.getModelElementById<ModelElementInstance>(srcId)
            if (srcElement is Gateway) {
                computeGatewayBranchRoute(s, t)?.let { route ->
                    edges[sf.id] = route
                }
            }
        }
    }

    private fun computeGatewayBranchRoute(s: Rect, t: Rect): List<Point>? {
        val startX = s.x + s.w / 2.0
        val targetCy = t.y + t.h / 2.0
        val isTargetBelow = t.y >= s.y + s.h - BpmnPlacementPass.POSITION_EPSILON
        val isTargetAbove = t.y + t.h <= s.y + BpmnPlacementPass.POSITION_EPSILON

        return when {
            isTargetBelow -> {
                val startY = s.y + s.h
                when {
                    t.x >= startX -> listOf(Point(startX, startY), Point(startX, targetCy), Point(t.x, targetCy))
                    t.x + t.w <= startX -> listOf(Point(startX, startY), Point(startX, targetCy), Point(t.x + t.w, targetCy))
                    else -> listOf(Point(startX, startY), Point(startX, t.y))
                }
            }
            isTargetAbove -> {
                val startY = s.y
                when {
                    t.x >= startX -> listOf(Point(startX, startY), Point(startX, targetCy), Point(t.x, targetCy))
                    t.x + t.w <= startX -> listOf(Point(startX, startY), Point(startX, targetCy), Point(t.x + t.w, targetCy))
                    else -> listOf(Point(startX, startY), Point(startX, t.y + t.h))
                }
            }
            else -> null
        }
    }

    /**
     * For non-exception sequence flows, copies ELK routing sections as waypoints.
     * Exception edges (boundary→handler) are already handled by [reconcileExceptionEdges].
     *
     * Any flow whose source or target was moved into the exception lane
     * ([BpmnPlacementPass.pushExceptionHandlersBelow]) is re-routed with a clean orthogonal path
     * from the source's border to the target's border, because ELK's original section no longer
     * matches the moved shapes (and would otherwise cut across the main flow).
     */
    private fun placeSequenceEdgeWaypoints(
        model: BpmnModelInstance,
        skeleton: ElkSkeleton,
        shapes: Map<String, Rect>,
        exceptionNodes: Set<String>,
        edges: MutableMap<String, List<Point>>,
    ) {
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }

        model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.source?.id !in boundaryIds && it.id !in edges }
            .sortedBy { it.id }
            .forEach { sf ->
                val srcId = sf.source?.id
                val tgtId = sf.target?.id
                val touchesMoved = srcId in exceptionNodes || tgtId in exceptionNodes
                if (touchesMoved && srcId != null && tgtId != null) {
                    val s = shapes[srcId]
                    val t = shapes[tgtId]
                    if (s != null && t != null) {
                        edges[sf.id] = routeOrthogonalBetween(s, t)
                        return@forEach
                    }
                }
                val elkEdge = skeleton.edgeMap[sf.id] ?: return@forEach
                val section = elkEdge.sections.firstOrNull() ?: return@forEach
                // ELK edge coordinates are relative to the edge's containing (LCA) node.
                val (ox, oy) = BpmnPlacementPass.edgeContainerOffset(elkEdge)
                val waypoints = mutableListOf(Point(section.startX + ox, section.startY + oy))
                section.bendPoints.mapTo(waypoints) { Point(it.x + ox, it.y + oy) }
                waypoints.add(Point(section.endX + ox, section.endY + oy))
                edges[sf.id] = waypoints
            }
    }

    /**
     * Straightens any sequence flow whose endpoints sit on the same horizontal row with a clear
     * corridor between them, replacing a stale ELK detour with a direct border-to-border line.
     *
     * ELK routes around obstacles; when the placement pass later relocates a node (e.g. pushes an
     * exception handler down into its own lane), an edge ELK had routed *around* that node keeps
     * its now-pointless detour. This pass detects the case — source and target share a centre Y,
     * are horizontally separated, and no other placed shape's box intrudes on that row between
     * them — and draws a straight horizontal line instead.
     */
    private fun straightenClearHorizontalEdges(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
    ) {
        model.getModelElementsByType(SequenceFlow::class.java).forEach { sf ->
            val id = sf.id
            val s = shapes[sf.source?.id] ?: return@forEach
            val t = shapes[sf.target?.id] ?: return@forEach
            if (id !in edges) return@forEach
            val sCy = s.y + s.h / 2.0
            // Treat near-aligned rows as the same row (small ELK jitter tolerated).
            if (kotlin.math.abs(sCy - (t.y + t.h / 2.0)) > SAME_ROW_TOLERANCE) return@forEach
            val leftBox = if (s.x <= t.x) s else t
            val rightBox = if (s.x <= t.x) t else s
            val gap = Rect(leftBox.x + leftBox.w, sCy, rightBox.x - (leftBox.x + leftBox.w), 0.0)
            if (gap.w > 0.0 && corridorClear(model, shapes, s, t, gap)) {
                edges[id] = listOf(Point(gap.x, sCy), Point(gap.x + gap.w, sCy))
            }
        }
    }

    /**
     * True if no shape other than [s]/[t] intrudes on the horizontal corridor [gap] — a zero-height
     * rectangle at the row's centre Y spanning the x gap between the two shapes.
     */
    private fun corridorClear(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        s: Rect,
        t: Rect,
        gap: Rect,
    ): Boolean {
        val gapR = gap.x + gap.w
        return shapes.entries.none { (id, b) ->
            if (b === s || b === t) {
                false
            } else {
                val isOverlappingX = b.x < gapR && b.x + b.w > gap.x
                val isOverlappingY = b.y < gap.y + 1.0 && b.y + b.h > gap.y - 1.0
                val isObstacle = isOverlappingX && isOverlappingY
                val elem = model.getModelElementById<ModelElementInstance>(id)
                val isContainer = elem is SubProcess || elem is Group
                isObstacle && !isContainer
            }
        }
    }

    /**
     * Routes each backward (loop) sequence flow up and over the top of the flow, the BPMN
     * convention for a cycle edge — instead of leaving it collapsed onto the forward edge along
     * the same row (where it renders as an invisible overlap of the two opposite-direction edges).
     *
     * A loop edge is one whose source sits to the RIGHT of its target on (roughly) the same row —
     * i.e. the flow runs leftward, back to an earlier node. The route exits the source's TOP, rises
     * above the tallest shape spanned horizontally, runs left, then drops into the target's TOP.
     */
    private fun routeLoopBackEdges(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
    ) {
        val nonContainers = shapes.filterKeys { id ->
            val elem = model.getModelElementById<ModelElementInstance>(id)
            elem !is SubProcess && elem !is Group
        }
        val allBoxes = nonContainers.values.toList()
        model.getModelElementsByType(SequenceFlow::class.java).forEach { sf ->
            val id = sf.id
            if (id !in edges) return@forEach
            val s = shapes[sf.source?.id] ?: return@forEach
            val t = shapes[sf.target?.id] ?: return@forEach
            val sCx = s.x + s.w / 2.0
            val tCx = t.x + t.w / 2.0
            // Loop edge: target is to the LEFT of the source and they share (roughly) a row.
            if (tCx >= sCx - BpmnPlacementPass.POSITION_EPSILON) return@forEach
            if (kotlin.math.abs((s.y + s.h / 2.0) - (t.y + t.h / 2.0)) > SAME_ROW_TOLERANCE) return@forEach
            // Clear the top of every shape whose x-span overlaps the loop's horizontal extent, so
            // the arc passes above intervening nodes rather than through them.
            val spanLeft = t.x
            val spanRight = s.x + s.w
            val topMost = allBoxes
                .filter { it.x < spanRight && it.x + it.w > spanLeft }
                .minOfOrNull { it.y } ?: minOf(s.y, t.y)
            val arcY = topMost - LOOP_ARC_CLEARANCE
            edges[id] = listOf(
                Point(sCx, s.y), // exit source top
                Point(sCx, arcY), // rise
                Point(tCx, arcY), // run left over the top
                Point(tCx, t.y), // drop into target top
            )
        }
    }

    /**
     * Clean orthogonal route between two shapes' borders. Exits [from] on the side facing [to]
     * and, crucially, ENTERS [to] on its near vertical edge (left if [to] is to the right, right
     * if to the left) at [to]'s centre height — so the arrowhead lands on the target's border,
     * not in its middle. Used when a shape has been relocated (exception lane) so ELK's original
     * route no longer applies.
     *
     * The final bottom-entry convention for edges that rise from a below node into a rejoin target
     * is applied uniformly afterwards by [enterRejoinTargetsFromBelow]; this routine only needs to
     * produce a clean orthogonal path.
     */
    private fun routeOrthogonalBetween(from: Rect, to: Rect): List<Point> {
        val fromCx = from.x + from.w / 2.0
        val fromCy = from.y + from.h / 2.0
        val toCy = to.y + to.h / 2.0
        val exitRight = to.x + to.w / 2.0 >= fromCx
        val startX = if (exitRight) from.x + from.w else from.x
        // Enter the target on the vertical edge facing the source, at the target's centre height.
        val endX = if (exitRight) to.x else to.x + to.w
        // Exit source horizontally at its centre height, run vertically at the target's near-edge
        // x to the target's centre height, then a short horizontal stub into the target edge.
        return if (kotlin.math.abs(fromCy - toCy) < BpmnPlacementPass.POSITION_EPSILON) {
            listOf(Point(startX, fromCy), Point(endX, toCy))
        } else {
            listOf(
                Point(startX, fromCy),
                Point(endX, fromCy),
                Point(endX, toCy),
            )
        }
    }

    /**
     * BPMN convention: a sequence flow rising from a node that sits entirely below its target
     * (a lower branch or an exception-lane handler merging back into the happy line) should enter
     * the target's BOTTOM edge, pointing up into it — not land on the target's left/right side.
     *
     * Applies uniformly to every sequence flow whose source shape is fully below its target shape,
     * regardless of how the edge was routed (ELK section or [routeOrthogonalBetween]). Rebuilds the
     * route as: exit the source's top at its centre-X, rise to the target's bottom edge, and step
     * horizontally to the target's centre-X so the arrowhead lands on the bottom border. Idempotent
     * and geometry-driven, so it is the single place this convention lives.
     */
    private fun enterRejoinTargetsFromBelow(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
    ) {
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }
        model.getModelElementsByType(SequenceFlow::class.java).forEach { sf ->
            val id = sf.id
            if (id !in edges) return@forEach
            // Boundary→handler edges drop DOWN into a lane and are owned by routeExceptionEdge.
            if (sf.source?.id in boundaryIds) return@forEach
            val s = shapes[sf.source?.id] ?: return@forEach
            val t = shapes[sf.target?.id] ?: return@forEach
            // Source must sit fully below the target (its top at/below the target's bottom).
            if (s.y < t.y + t.h - BpmnPlacementPass.POSITION_EPSILON) return@forEach
            val srcCx = s.x + s.w / 2.0
            val tgtCx = t.x + t.w / 2.0
            val startY = s.y // source top
            val endY = t.y + t.h // target bottom edge
            edges[id] = when {
                // Source is to the left: exit right edge, run horizontally to tgtCx, rise to target bottom
                s.x + s.w <= tgtCx -> {
                    val exitX = s.x + s.w
                    val exitY = s.y + s.h / 2.0
                    listOf(Point(exitX, exitY), Point(tgtCx, exitY), Point(tgtCx, endY))
                }
                // Source is to the right: exit left edge, run horizontally to tgtCx, rise to target bottom
                s.x >= tgtCx -> {
                    val exitX = s.x
                    val exitY = s.y + s.h / 2.0
                    listOf(Point(exitX, exitY), Point(tgtCx, exitY), Point(tgtCx, endY))
                }
                // Directly below or overlapping: a single vertical rise into the bottom edge.
                else -> {
                    listOf(Point(srcCx, startY), Point(tgtCx, endY))
                }
            }
        }
    }

    /** The point halfway along the total length of a polyline. */
    internal fun polylineMidpoint(wps: List<Point>): Point {
        val total = (1 until wps.size).sumOf { i -> dist(wps[i - 1], wps[i]) }
        if (total == 0.0) return wps.first()
        var remaining = total / 2.0
        for (i in 1 until wps.size) {
            val seg = dist(wps[i - 1], wps[i])
            if (remaining <= seg) {
                val t = if (seg == 0.0) 0.0 else remaining / seg
                return Point(
                    wps[i - 1].x + (wps[i].x - wps[i - 1].x) * t,
                    wps[i - 1].y + (wps[i].y - wps[i - 1].y) * t,
                )
            }
            remaining -= seg
        }
        return wps.last()
    }

    internal fun dist(a: Point, b: Point): Double = kotlin.math.hypot(b.x - a.x, b.y - a.y)

    // ── Named rule: association edges from shape centres ───────────────────────

    /**
     * Places association edges as straight lines between shape centres,
     * matching the 557-2 behaviour preserved for this stage.
     */
    internal fun placeAssociationEdges(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
    ) {
        model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Association::class.java)
            .sortedBy { it.id }
            .forEach { assoc ->
                val sourceRect = shapes[assoc.source?.id] ?: return@forEach
                val targetRect = shapes[assoc.target?.id] ?: return@forEach
                // Attach the connector at each shape's border facing the other shape, not the centre.
                edges[assoc.id] = listOf(
                    borderPoint(sourceRect, targetRect),
                    borderPoint(targetRect, sourceRect),
                )
            }
    }

    /**
     * Returns the point on [rect]'s border that faces [toward] — the intersection of the border
     * with the line from [rect]'s centre to [toward]'s centre. Used so connectors start/end on
     * shape edges, never in the middle of a node.
     */
    private fun borderPoint(rect: Rect, toward: Rect): Point {
        val cx = rect.x + rect.w / 2.0
        val cy = rect.y + rect.h / 2.0
        val tx = toward.x + toward.w / 2.0
        val ty = toward.y + toward.h / 2.0
        val dx = tx - cx
        val dy = ty - cy
        if (dx == 0.0 && dy == 0.0) return Point(cx, cy)
        // Scale the direction vector to hit the nearest border (half-width / half-height).
        val hw = rect.w / 2.0
        val hh = rect.h / 2.0
        val scaleX = if (dx != 0.0) hw / kotlin.math.abs(dx) else Double.MAX_VALUE
        val scaleY = if (dy != 0.0) hh / kotlin.math.abs(dy) else Double.MAX_VALUE
        val scale = kotlin.math.min(scaleX, scaleY)
        return Point(cx + dx * scale, cy + dy * scale)
    }
}
