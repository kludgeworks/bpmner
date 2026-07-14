/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.eclipse.elk.alg.layered.options.LayerConstraint
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy
import org.eclipse.elk.alg.layered.options.OrderingStrategy
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.options.PortSide
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Verifies that the ELK API behaves as the layout design assumes.
 * These tests use minimal hand-built ELK graphs — no BPMN involved.
 * They must pass before any production code is written for 557-3.
 */
class ElkOptionBehaviourTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun registerMetadata() {
            LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
        }

        private fun runLayout(root: org.eclipse.elk.graph.ElkNode) {
            RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())
        }

        private fun applyBaseOptions(node: org.eclipse.elk.graph.ElkNode) {
            node.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
            node.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
            node.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
            node.setProperty(CoreOptions.RANDOM_SEED, 1)
        }
    }

    @Test
    fun `INCLUDE_CHILDREN compound node sizes around its children after layout`() {
        val root = ElkGraphUtil.createGraph()
        applyBaseOptions(root)
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)

        val compound = ElkGraphUtil.createNode(root)
        compound.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)

        val child1 = ElkGraphUtil.createNode(compound)
        child1.width = 100.0
        child1.height = 80.0

        val child2 = ElkGraphUtil.createNode(compound)
        child2.width = 100.0
        child2.height = 80.0

        ElkGraphUtil.createSimpleEdge(child1, child2)

        runLayout(root)

        // Compound node must be large enough to contain both children
        assertTrue(compound.width > 100.0, "Compound width ${compound.width} should exceed single child width 100")
        assertTrue(compound.height >= 80.0, "Compound height ${compound.height} should be at least child height 80")
        // Children must fit inside compound bounds
        assertTrue(child1.x >= 0.0, "child1.x=${child1.x} should be non-negative (relative to compound)")
        assertTrue(child2.x >= 0.0, "child2.x=${child2.x} should be non-negative (relative to compound)")
        assertTrue(child1.x + child1.width <= compound.width, "child1 right edge must fit inside compound")
        assertTrue(child2.x + child2.width <= compound.width, "child2 right edge must fit inside compound")
    }

    @Test
    fun `PADDING on a compound node is respected after layout`() {
        val root = ElkGraphUtil.createGraph()
        applyBaseOptions(root)
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)

        val padding = 40.0
        val compound = ElkGraphUtil.createNode(root)
        compound.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
        compound.setProperty(CoreOptions.PADDING, ElkPadding(padding))

        val child = ElkGraphUtil.createNode(compound)
        child.width = 100.0
        child.height = 80.0

        runLayout(root)

        // Child should be offset by at least the padding from the compound's top-left
        assertTrue(child.x >= padding - 1.0, "child.x=${child.x} should be >= padding $padding")
        assertTrue(child.y >= padding - 1.0, "child.y=${child.y} should be >= padding $padding")
        // Compound should be larger than child + padding on both sides
        assertTrue(compound.width >= child.width + padding, "compound.width too small for child + left padding")
        assertTrue(compound.height >= child.height + padding, "compound.height too small for child + top padding")
    }

    @Test
    fun `FIXED_SIDE port with PORT_SIDE SOUTH lands on the node bottom after layout`() {
        val root = ElkGraphUtil.createGraph()
        applyBaseOptions(root)

        val node = ElkGraphUtil.createNode(root)
        node.width = 100.0
        node.height = 80.0
        node.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)

        val port = ElkGraphUtil.createPort(node)
        port.width = 10.0
        port.height = 10.0
        port.setProperty(CoreOptions.PORT_SIDE, PortSide.SOUTH)

        // Add a target node and edge through the port so ELK processes it
        val target = ElkGraphUtil.createNode(root)
        target.width = 100.0
        target.height = 80.0
        val edge = ElkGraphUtil.createEdge(root)
        edge.sources.add(port)
        edge.targets.add(target)

        runLayout(root)

        // Port y should be at or near the node bottom (node.height - port.height)
        val expectedPortY = node.height - port.height
        assertTrue(
            port.y >= expectedPortY - 1.0,
            "SOUTH port y=${port.y} should be near node bottom (expected ~$expectedPortY)",
        )
    }

    @Test
    fun `createSimpleEdge between two children of same compound is contained in that compound`() {
        val root = ElkGraphUtil.createGraph()
        applyBaseOptions(root)
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)

        val compound = ElkGraphUtil.createNode(root)
        compound.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)

        val child1 = ElkGraphUtil.createNode(compound)
        child1.width = 100.0
        child1.height = 80.0
        val child2 = ElkGraphUtil.createNode(compound)
        child2.width = 100.0
        child2.height = 80.0

        val edge = ElkGraphUtil.createSimpleEdge(child1, child2)

        // Before layout, the edge container should already be the compound node (LCA)
        assertTrue(
            edge.containingNode == compound,
            "Edge should be contained in compound node (LCA), but was in ${edge.containingNode?.identifier ?: "root"}",
        )
    }

    @Test
    fun `child node coordinates after layout are relative to parent compound node`() {
        val root = ElkGraphUtil.createGraph()
        applyBaseOptions(root)
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)

        val compound = ElkGraphUtil.createNode(root)
        compound.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
        compound.setProperty(CoreOptions.PADDING, ElkPadding(20.0))

        val child = ElkGraphUtil.createNode(compound)
        child.width = 100.0
        child.height = 80.0

        runLayout(root)

        // compound has a position in root coordinates
        assertTrue(compound.x >= 0.0, "compound.x should be non-negative")
        // child.x/y are relative to compound, so they should be < compound dimensions
        assertTrue(
            child.x < compound.width,
            "child.x=${child.x} should be relative to compound (< ${compound.width}), not absolute",
        )
        assertTrue(
            child.y < compound.height,
            "child.y=${child.y} should be relative to compound (< ${compound.height}), not absolute",
        )
    }

    // ── AD-557-10 Phase-boundary risk: south-port routing proof ──────────────
    // These two tests verify the empirical assumption that underpins AD-557-10:
    // a FIXED_SIDE/SOUTH port on a flow node carries its exception edge with real
    // routing sections (non-zero, non-null). If either test fails the architect
    // must be consulted before proceeding (see PLAN-557-3.md Phase-boundary risk).

    @Test
    fun `SOUTH port exception edge produces non-empty routing sections`() {
        // Simulates: host node (task) with a SOUTH boundary port → exception handler
        val root = ElkGraphUtil.createGraph()
        applyBaseOptions(root)
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.SEPARATE_CHILDREN)

        val host = ElkGraphUtil.createNode(root)
        host.width = 100.0
        host.height = 80.0
        host.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)

        val port = ElkGraphUtil.createPort(host)
        port.width = 10.0
        port.height = 10.0
        port.setProperty(CoreOptions.PORT_SIDE, PortSide.SOUTH)

        val handler = ElkGraphUtil.createNode(root)
        handler.width = 100.0
        handler.height = 80.0

        // Edge from SOUTH port to handler — simulates the exception edge
        val edge = ElkGraphUtil.createEdge(root)
        edge.sources.add(port)
        edge.targets.add(handler)

        runLayout(root)

        // The edge must have at least one routing section with non-zero coordinates
        assertTrue(
            edge.sections.isNotEmpty(),
            "Exception edge from SOUTH port must have routing sections after layout",
        )
        val section = edge.sections.first()
        val hasNonZero = section.startX != 0.0 ||
            section.startY != 0.0 ||
            section.endX != 0.0 ||
            section.endY != 0.0
        assertTrue(
            hasNonZero,
            "Exception edge section must have non-zero coordinates: " +
                "start=(${section.startX},${section.startY}) end=(${section.endX},${section.endY})",
        )
    }

    @Test
    fun `cross-hierarchy SOUTH port edge (boundary on subprocess) produces routing sections`() {
        // Simulates: boundary event on a subprocess host — the port is on the compound node,
        // the exception handler is a root-level node, so the edge crosses hierarchy.
        val root = ElkGraphUtil.createGraph()
        applyBaseOptions(root)
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)

        val compound = ElkGraphUtil.createNode(root)
        compound.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
        compound.setProperty(CoreOptions.PADDING, ElkPadding(30.0))
        compound.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)

        val compoundPort = ElkGraphUtil.createPort(compound)
        compoundPort.width = 10.0
        compoundPort.height = 10.0
        compoundPort.setProperty(CoreOptions.PORT_SIDE, PortSide.SOUTH)

        // Child inside compound
        val child = ElkGraphUtil.createNode(compound)
        child.width = 100.0
        child.height = 80.0

        // Handler at root level (cross-hierarchy target)
        val handler = ElkGraphUtil.createNode(root)
        handler.width = 100.0
        handler.height = 80.0

        // Cross-hierarchy exception edge from compound's SOUTH port to root handler
        val crossEdge = ElkGraphUtil.createEdge(root)
        crossEdge.sources.add(compoundPort)
        crossEdge.targets.add(handler)

        runLayout(root)

        // The cross-hierarchy edge must have sections
        assertTrue(
            crossEdge.sections.isNotEmpty(),
            "Cross-hierarchy exception edge from SOUTH port must have routing sections",
        )
        val section = crossEdge.sections.first()
        val hasNonZero = section.startX != 0.0 ||
            section.startY != 0.0 ||
            section.endX != 0.0 ||
            section.endY != 0.0
        assertTrue(
            hasNonZero,
            "Cross-hierarchy edge section must have non-zero coordinates: " +
                "start=(${section.startX},${section.startY}) end=(${section.endX},${section.endY})",
        )
    }

    // ── AD-557-11 Spike proofs: phase-1 ELK constraints replace phase-2 moves ──

    /**
     * Spike proof 1 (AD-557-11): NODE_PLACEMENT_STRATEGY = NETWORK_SIMPLEX keeps a
     * primary flat chain on a single centre-Y (within ε), so the `snapBaseline` post-move
     * can be deleted and replaced by this phase-1 constraint.
     *
     * Graph: start → task1 → task2 → end  (all same size, no branches)
     * Asserts all four nodes share the same centre-Y after layout (within 2px tolerance).
     */
    @Test
    fun `spike - NETWORK_SIMPLEX node placement keeps flat chain on one centre-Y`() {
        val root = ElkGraphUtil.createGraph()
        applyBaseOptions(root)
        root.setProperty(
            LayeredOptions.NODE_PLACEMENT_STRATEGY,
            NodePlacementStrategy.NETWORK_SIMPLEX,
        )
        root.setProperty(CoreOptions.RANDOM_SEED, 1)

        val start = ElkGraphUtil.createNode(root).also {
            it.width = 36.0
            it.height = 36.0
        }
        val task1 = ElkGraphUtil.createNode(root).also {
            it.width = 100.0
            it.height = 80.0
        }
        val task2 = ElkGraphUtil.createNode(root).also {
            it.width = 100.0
            it.height = 80.0
        }
        val end = ElkGraphUtil.createNode(root).also {
            it.width = 36.0
            it.height = 36.0
        }

        ElkGraphUtil.createSimpleEdge(start, task1)
        ElkGraphUtil.createSimpleEdge(task1, task2)
        ElkGraphUtil.createSimpleEdge(task2, end)

        runLayout(root)

        // Collect centre-Y for each node
        val centreYs = listOf(start, task1, task2, end).map { it.y + it.height / 2.0 }
        val minY = centreYs.min()
        val maxY = centreYs.max()
        val spread = maxY - minY

        // Within a linear chain, NETWORK_SIMPLEX should keep all nodes on one horizontal row.
        // We allow 2px jitter (ELK may add a sub-pixel offset for the differently-sized events).
        assertTrue(
            spread <= 2.0,
            "NETWORK_SIMPLEX should align flat chain to single centre-Y: " +
                "centres=$centreYs spread=$spread (expected ≤2.0)",
        )
    }

    /**
     * Spike proof 2 (AD-557-11): LAYERING_LAYER_CONSTRAINT = FIRST/LAST + CONSIDER_MODEL_ORDER
     * pins the start node to the leftmost layer and the end node to the rightmost layer,
     * replacing `snapBaseline`'s need to fix node ordering.
     *
     * Graph: start(FIRST) → task → end(LAST)
     * Asserts start has smaller x than task, and task has smaller x than end.
     */
    @Test
    fun `spike - LAYER_CONSTRAINT FIRST and LAST pin start and end nodes to outer layers`() {
        val root = ElkGraphUtil.createGraph()
        applyBaseOptions(root)
        root.setProperty(
            LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY,
            OrderingStrategy.NODES_AND_EDGES,
        )
        root.setProperty(CoreOptions.RANDOM_SEED, 1)

        val start = ElkGraphUtil.createNode(root).also {
            it.width = 36.0
            it.height = 36.0
        }
        start.setProperty(LayeredOptions.LAYERING_LAYER_CONSTRAINT, LayerConstraint.FIRST)

        val task = ElkGraphUtil.createNode(root).also {
            it.width = 100.0
            it.height = 80.0
        }

        val end = ElkGraphUtil.createNode(root).also {
            it.width = 36.0
            it.height = 36.0
        }
        end.setProperty(LayeredOptions.LAYERING_LAYER_CONSTRAINT, LayerConstraint.LAST)

        ElkGraphUtil.createSimpleEdge(start, task)
        ElkGraphUtil.createSimpleEdge(task, end)

        runLayout(root)

        // start must be to the left of task, task to the left of end (RIGHT direction)
        assertTrue(
            start.x < task.x,
            "Start (x=${start.x}) must be to the left of task (x=${task.x}) with LAYER_CONSTRAINT FIRST",
        )
        assertTrue(
            task.x < end.x,
            "Task (x=${task.x}) must be to the left of end (x=${end.x}) with LAYER_CONSTRAINT LAST",
        )
    }

    /**
     * Spike proof 3 (AD-557-11, key decision gate): can ELK place the exception handler
     * **below** the main flow in one pass, using only phase-1 constraints — so
     * `pushExceptionHandlersBelow` (the post-ELK node move) can be deleted?
     *
     * Graph:
     *   host (task, SOUTH boundary port) → main successor (end event)
     *   boundary port → exception handler
     *
     * Inserted in model order: host, successor, handler (handler last so considerModelOrder
     * puts it below).  With SEPARATE_CONNECTED_COMPONENTS the exception subgraph is its own
     * component; its vertical band position relative to the main flow is what we probe here.
     *
     * Decision gate:
     * - PASS: handler.y  >  max(host.y + host.height, successor.y + successor.height)
     *   → proceed to delete pushExceptionHandlersBelow and its repair functions.
     * - FAIL: handler is at or above the main flow
     *   → stop and escalate to /architect 557 (cannot meet AD-557-11 for this clause
     *     without a post-ELK move, which the architecture forbids).
     */
    @Test
    fun `spike - exception handler placed below main flow by ELK in one pass (AD-557-11 decision gate)`() {
        val root = ElkGraphUtil.createGraph()
        applyBaseOptions(root)
        root.setProperty(CoreOptions.RANDOM_SEED, 1)
        // Insert main flow first, exception handler last so considerModelOrder biases it below.
        root.setProperty(
            LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY,
            OrderingStrategy.NODES_AND_EDGES,
        )
        // With SEPARATE_CONNECTED_COMPONENTS each disconnected subgraph is laid out independently;
        // ELK stacks separate components vertically (for RIGHT direction: horizontally by default,
        // then stacked).  We rely on model/insert order to determine stacking.
        root.setProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS, true)

        // Main flow: host → successor (inserted first → component 1)
        val host = ElkGraphUtil.createNode(root).also {
            it.width = 100.0
            it.height = 80.0
        }
        host.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)

        val port = ElkGraphUtil.createPort(host)
        port.width = 10.0
        port.height = 10.0
        port.setProperty(CoreOptions.PORT_SIDE, PortSide.SOUTH)

        val successor = ElkGraphUtil.createNode(root).also {
            it.width = 36.0
            it.height = 36.0
        }
        ElkGraphUtil.createSimpleEdge(host, successor) // connects host + successor → component 1

        // Exception handler (inserted last → component 2; model-order bias puts it second)
        val handler = ElkGraphUtil.createNode(root).also {
            it.width = 100.0
            it.height = 80.0
        }
        // Edge from SOUTH port to handler — note: with SEPARATE_CONNECTED_COMPONENTS this edge
        // CONNECTS the handler to the main flow's component, so they become ONE component.
        // That is fine: ELK will rank/order within the single component; model order and the
        // SOUTH port force the handler to a row below the host.
        val exEdge = ElkGraphUtil.createEdge(root)
        exEdge.sources.add(port)
        exEdge.targets.add(handler)

        runLayout(root)

        // Record results for the decision gate
        val mainBottom = maxOf(
            host.y + host.height,
            successor.y + successor.height,
        )
        val handlerTop = handler.y

        // Decision gate: handler must start at or below the main flow's bottom edge.
        // We allow a small epsilon because ELK's layered algorithm may co-rank the handler
        // in the SOUTH port's layer but still place it below due to the port direction.
        assertTrue(
            handlerTop >= mainBottom - 1.0,
            "AD-557-11 decision gate FAIL: exception handler top (${handler.y}) must be at or " +
                "below main flow bottom ($mainBottom). " +
                "Host: y=${host.y} h=${host.height}, Successor: y=${successor.y} h=${successor.height}. " +
                "If this fails, escalate to /architect 557 — ELK cannot place the exception band " +
                "below the main flow in one pass without a post-ELK node move.",
        )
    }
}
