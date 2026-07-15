/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.SequenceFlow

/**
 * Declared move and repair: rigid whole-component X-translation.
 *
 * Move shifts every handler-component node's left edge to be at least its host's right edge +
 * [HANDLER_COMPONENT_X_GAP], preserving intra-component relative offsets (rigid translation).
 *
 * Repair corrects sequence-flow waypoints whose source or target was shifted by Move to re-anchor
 * from the shifted positions. Exception-edge waypoints are not touched (they are produced
 * by [ExceptionEdgeRoutes] and already use placed shape positions).
 */
internal object HandlerComponentAlignment {

    /** Minimum horizontal gap between a host's right edge and the handler component's left edge. */
    internal const val HANDLER_COMPONENT_X_GAP = 30.0

    /** Minimum vertical gap between the main-flow bottom and the top of a handler component. */
    private const val HANDLER_COMPONENT_Y_GAP = 40.0

    private class AlignmentState(
        val successors: Map<String, List<String>>,
        val boundaryIds: Set<String>,
        val mainFlow: Set<String>,
        val ctx: PlacementContext,
    )

    val Move: PlacementProcessor = PlacementProcessor { ctx ->
        val boundaryIds = ctx.model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }
        if (boundaryIds.isEmpty()) return@PlacementProcessor

        val successors = buildFlowSuccessors(ctx)
        val mainFlow = reachableFrom(
            seeds = ctx.model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.StartEvent::class.java)
                .map { it.id },
            successors = successors,
            exclude = boundaryIds,
        )
        val handlerSeeds = boundaryIds.flatMap { successors[it].orEmpty() }
        if (handlerSeeds.isEmpty()) return@PlacementProcessor

        val mainBottom = mainFlow.mapNotNull { ctx.shapes[it] }.maxOfOrNull { it.y + it.h } ?: 0.0
        var nextFloor = mainBottom + HANDLER_COMPONENT_Y_GAP

        val state = AlignmentState(successors, boundaryIds, mainFlow, ctx)

        ctx.model.getModelElementsByType(BoundaryEvent::class.java)
            .sortedBy { it.id }
            .forEach { be ->
                nextFloor = shiftHandlerComponentForBoundary(be, state, nextFloor)
            }
    }

    val Repair: PlacementProcessor = PlacementProcessor { ctx ->
        val shiftedIds = ctx.moves
            .filter { it.value.owner == "HandlerComponentAlignment" }
            .keys
        if (shiftedIds.isEmpty()) return@PlacementProcessor

        val boundaryIds = ctx.model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }
        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.source?.id !in boundaryIds }
            .forEach { sf ->
                val srcId = sf.source?.id
                val tgtId = sf.target?.id
                val srcMove = ctx.moves[srcId]?.takeIf { it.owner == "HandlerComponentAlignment" }
                val tgtMove = ctx.moves[tgtId]?.takeIf { it.owner == "HandlerComponentAlignment" }
                if (srcMove == null && tgtMove == null) return@forEach
                if (ctx.edges[sf.id] == null) return@forEach
                when {
                    srcMove != null && tgtMove == null ->
                        routeRejoinEdge(srcId, tgtId, ctx.shapes, ctx.edges, sf.id)
                    tgtMove != null && srcMove == null ->
                        routeForwardToHandlerEdge(srcId, tgtId, ctx.shapes, ctx.edges, sf.id)
                    else -> {
                        // Both handler nodes: apply the source shift uniformly.
                        val move = srcMove ?: tgtMove!!
                        ctx.edges[sf.id] = ctx.edges[sf.id]!!
                            .map { it.copy(x = it.x + move.dx, y = it.y + move.dy) }
                    }
                }
            }
    }

    private fun buildFlowSuccessors(ctx: PlacementContext): Map<String, List<String>> {
        val map = mutableMapOf<String, MutableList<String>>()
        ctx.model.getModelElementsByType(SequenceFlow::class.java).forEach { sf ->
            val s = sf.source?.id ?: return@forEach
            val t = sf.target?.id ?: return@forEach
            map.getOrPut(s) { mutableListOf() }.add(t)
        }
        return map
    }

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

    private fun shiftHandlerComponentForBoundary(
        be: BoundaryEvent,
        state: AlignmentState,
        floor: Double,
    ): Double {
        val hostId = be.attachedTo?.id ?: return floor
        val hostRight = (state.ctx.shapes[hostId] ?: return floor).let { it.x + it.w }
        val thisHandlers = reachableFrom(
            seeds = state.successors[be.id].orEmpty(),
            successors = state.successors,
            exclude = state.mainFlow + state.boundaryIds,
        )
        if (thisHandlers.isEmpty()) return floor

        val handlerLeft = thisHandlers.mapNotNull { state.ctx.shapes[it]?.x }.minOrNull() ?: return floor
        val xShift = (hostRight + HANDLER_COMPONENT_X_GAP) - handlerLeft

        val handlerTop = thisHandlers.mapNotNull { state.ctx.shapes[it]?.y }.minOrNull() ?: return floor
        val yShift = maxOf(0.0, floor - handlerTop)

        thisHandlers.forEach { id ->
            state.ctx.shapes[id]?.let { r ->
                state.ctx.shapes[id] = r.copy(x = r.x + xShift, y = r.y + yShift)
                state.ctx.moves[id] = MoveRecord("HandlerComponentAlignment", xShift, yShift)
            }
        }

        val chainBottom = thisHandlers.mapNotNull { state.ctx.shapes[it] }.maxOfOrNull { it.y + it.h } ?: floor
        return chainBottom + HANDLER_COMPONENT_Y_GAP
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
}
