/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.ElkSkeleton
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.Lane

/**
 * Single-responsibility processor in the ordered placement pipeline.
 *
 * Each implementation applies exactly one named BPMN placement rule to the shared
 * [PlacementContext] and records any node moves in the ledger for guard verification.
 */
internal fun interface PlacementProcessor {
    /** Apply this processor's rule to [ctx], mutating its maps in place. */
    fun process(ctx: PlacementContext)
}

/**
 * The mutable shared state that the ordered processor pipeline threads through.
 *
 * Every coordinate mutation of a flow-node shape that is a declared move (i.e. one of the
 * three moving conventions: [HandlerComponentAlignment], [SubprocessEndStraddle],
 * [SubprocessSpineCentring]) must record a [MoveRecord] in [moves] so the
 * no-undeclared-relocation guard can verify it.
 *
 * [BoundaryShapePlacement] is a sanctioned decoration and is NOT ledgered.
 */
internal class PlacementContext(
    val model: BpmnModelInstance,
    val skeleton: ElkSkeleton,
    val shapes: MutableMap<String, Rect>,
    val labels: MutableMap<String, Rect>,
    val edges: MutableMap<String, List<Point>>,
    val expanded: MutableSet<String>,
    /** Move ledger: nodeId → MoveRecord for every declared move. */
    val moves: MutableMap<String, MoveRecord> = mutableMapOf(),
    /** Lane map: lane ID → Lane, populated by BpmnPlacementPass.place from the model. */
    val laneMap: Map<String, Lane> = emptyMap(),
)

/**
 * Records a declared move made by one of the three moving conventions.
 *
 * [owner] names the convention that made the move (for guard error messages).
 * [dx] and [dy] are the absolute X and Y shifts applied to the node's shape.
 */
internal data class MoveRecord(
    val owner: String,
    val dx: Double,
    val dy: Double,
)
