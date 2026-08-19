/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.math.KVector
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * AD-730-06 decision spike: which stock `elk.layered` mechanism, if any, can express BPMN lane
 * bands as a routing input (declared before phase-5 edge routing) rather than a post-layout
 * translation. Builds the ELK graph directly (bypassing [BpmnToElkMapper]) so flow nodes sit
 * directly under one parent with no lane compound — the AD-730-06 target shape — letting each
 * candidate's properties be tested in isolation before any production code changes.
 *
 * Topology mirrors the reduced branch/rejoin regression fixture: a top-lane start/prepare, a
 * split, a top-lane branch and a lower-lane branch, a top-lane rejoin, and an end — two lanes,
 * one cross-lane branch, one cross-lane rejoin.
 */
class LaneConstraintSpikeTest {

    init {
        LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
    }

    /**
     * Falsification: partition-tagged lanes under `Direction.RIGHT` land side-by-side (ordered on
     * the flow axis), not stacked as top-to-bottom bands — confirming AD-730-05's partitioning
     * axis analysis before any candidate is tried.
     */
    @Test
    fun `partitioning under Direction RIGHT orders lanes horizontally, not vertically`() {
        val root = buildFlatGraph()
        root.children.forEach { node ->
            val laneIndex = node.identifier.laneIndexOf()
            node.setProperty(LayeredOptions.PARTITIONING_ACTIVATE, true)
            node.setProperty(LayeredOptions.PARTITIONING_PARTITION, laneIndex)
        }
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

        val topX = root.children.first { it.identifier == "top_prepare" }.x
        val lowerX = root.children.first { it.identifier == "lower_task" }.x
        assertTrue(
            topX != lowerX,
            "expected partitioning to separate lanes on X (the flow axis) under Direction.RIGHT, " +
                "not Y — got topX=$topX lowerX=$lowerX",
        )
    }

    /**
     * A1 — ordering only: `POSITION.y` = lane order key + `crossingMinimization.semiInteractive`.
     * Proves whether `SemiInteractiveCrossMinProcessor` orders each layer by declared lane index.
     * This alone does not guarantee cross-layer Y alignment (banding); it only orders within a
     * layer. Measured, not assumed, per AD-730-05.
     */
    @Test
    fun `A1 semiInteractive ordering sorts each layer by lane position but does not band across layers`() {
        val root = buildFlatGraph()
        applyA1(root)
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

        val topTaskY = root.children.first { it.identifier == "top_review" }.y
        val lowerTaskY = root.children.first { it.identifier == "lower_task" }.y
        // A1's contract is ordering, not banding: assert the mechanism ran (order held within the
        // layer that contains both), not that the two arbitrary-layer Y values line up as bands.
        assertTrue(
            topTaskY < lowerTaskY,
            "expected A1 in-layer ordering to place the top-lane node above the lower-lane node " +
                "within their shared layer — got topTaskY=$topTaskY lowerTaskY=$lowerTaskY",
        )
    }

    /**
     * A2 — ordering + banding: A1 plus each node's *input* `y` set to its lane band offset, with
     * `nodePlacement.strategy = INTERACTIVE`. Per the verified ELK 0.12.0 mechanics
     * (`InteractiveNodePlacer` keeps a normal node's imported `y` and only pushes down on
     * overlap), this should yield true cross-layer banding: every top-lane node lands above every
     * lower-lane node, in every layer.
     */
    @Test
    fun `A2 interactive placement bands every lane member across all layers`() {
        val root = buildFlatGraph()
        applyA1(root)
        root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.INTERACTIVE)
        root.children.forEach { node -> node.y = node.identifier.laneIndexOf() * LANE_BAND_HEIGHT }
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

        val topYs = root.children.filter { it.identifier.laneIndexOf() == 0 }.map { it.y }
        val lowerYs = root.children.filter { it.identifier.laneIndexOf() == 1 }.map { it.y }
        val maxTopY = topYs.max()
        val minLowerY = lowerYs.min()
        assertTrue(
            maxTopY < minLowerY,
            "expected every top-lane node's Y to stay below every lower-lane node's Y (true " +
                "banding) — got maxTopY=$maxTopY minLowerY=$minLowerY, topYs=$topYs lowerYs=$lowerYs",
        )
    }

    /**
     * Greedy-switch risk (AD-730-05): the hierarchical greedy switch is inactive by default and
     * only activates on an `INCLUDE_CHILDREN` root; this graph has no compound hierarchy at all
     * (AD-730-06's target — flow nodes sit directly under one parent), so the plain non-hierarchical
     * greedy switch is the only one that can run, and it must not undo A2's banding.
     */
    @Test
    fun `A2 banding survives the default greedy switch on a non-hierarchical graph`() {
        val root = buildFlatGraph()
        applyA1(root)
        root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.INTERACTIVE)
        root.children.forEach { node -> node.y = node.identifier.laneIndexOf() * LANE_BAND_HEIGHT }
        // Defaults only: no HIERARCHY_HANDLING is set on this graph, so greedy switch activation
        // follows the plain non-hierarchical branch of GraphConfigurator.activateGreedySwitchFor.
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

        val maxTopY = root.children.filter { it.identifier.laneIndexOf() == 0 }.maxOf { it.y }
        val minLowerY = root.children.filter { it.identifier.laneIndexOf() == 1 }.minOf { it.y }
        assertTrue(
            maxTopY < minLowerY,
            "expected the default greedy switch to leave A2's lane banding intact — got " +
                "maxTopY=$maxTopY minLowerY=$minLowerY",
        )
    }

    private fun applyA1(root: ElkNode) {
        root.setProperty(LayeredOptions.CROSSING_MINIMIZATION_SEMI_INTERACTIVE, true)
        root.children.forEach { node ->
            node.setProperty(LayeredOptions.POSITION, KVector(0.0, node.identifier.laneIndexOf().toDouble()))
        }
    }

    /** `top_*` nodes are lane 0; `lower_*` nodes are lane 1 — mirrors the regression fixture. */
    private fun String.laneIndexOf(): Int = if (startsWith("lower")) 1 else 0

    /**
     * Builds the branch/rejoin topology with every flow node as a direct child of the root graph
     * — no lane compound, no participant compound — the AD-730-06 target shape for Candidate A.
     */
    private fun buildFlatGraph(): ElkNode {
        val root = ElkGraphUtil.createGraph()
        root.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
        root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        root.setProperty(CoreOptions.RANDOM_SEED, 1)
        root.setProperty(CoreOptions.SPACING_NODE_NODE, 60.0)
        root.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, 90.0)

        val ids = listOf(
            "top_start",
            "top_prepare",
            "top_split",
            "top_review",
            "top_join",
            "top_end",
            "lower_task",
        )
        val nodes = ids.associateWith { id ->
            ElkGraphUtil.createNode(root).also {
                it.identifier = id
                it.width = 60.0
                it.height = 40.0
            }
        }

        fun edge(fromId: String, toId: String) = ElkGraphUtil.createSimpleEdge(nodes.getValue(fromId), nodes.getValue(toId))
        edge("top_start", "top_prepare")
        edge("top_prepare", "top_split")
        edge("top_split", "top_review")
        edge("top_split", "lower_task")
        edge("top_review", "top_join")
        edge("lower_task", "top_join")
        edge("top_join", "top_end")

        return root
    }

    private companion object {
        const val LANE_BAND_HEIGHT = 200.0
    }
}
