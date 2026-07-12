/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.alg.layered.options.LayeredOptions
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
}
