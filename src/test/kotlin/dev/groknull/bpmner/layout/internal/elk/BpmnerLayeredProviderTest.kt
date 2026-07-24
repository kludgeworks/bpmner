/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.elk

import org.eclipse.elk.alg.layered.graph.LGraph
import org.eclipse.elk.alg.layered.intermediate.LayerSizeAndGraphHeightCalculator
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.core.alg.ILayoutProcessor
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.core.util.IElkProgressMonitor
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Proves the carrier's processor-injection mechanism (AD-622-01) with the smallest real
 * consumer: a probe processor declared to run after a stock ELK processor, asserted to have run
 * in that slot. Uses a flat (non-hierarchical) hand-built graph — no BPMN involved, and no
 * algorithm registration, since [BpmnerLayeredProvider.layout] is invoked directly.
 */
class BpmnerLayeredProviderTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun registerMetadata() {
            LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
        }
    }

    @Test
    fun `injected processor runs immediately after its declared stock processor`() {
        val root = ElkGraphUtil.createGraph()
        root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        root.setProperty(CoreOptions.RANDOM_SEED, 1)

        val a = ElkGraphUtil.createNode(root)
        a.setDimensions(30.0, 30.0)
        val b = ElkGraphUtil.createNode(root)
        b.setDimensions(30.0, 30.0)
        ElkGraphUtil.createSimpleEdge(a, b)

        var ran = false
        val probe = ILayoutProcessor<LGraph> { _, _: IElkProgressMonitor -> ran = true }
        val injection = ProcessorInjection(LayerSizeAndGraphHeightCalculator::class.java, probe)

        BpmnerLayeredProvider(listOf(injection)).layout(root, BasicProgressMonitor())

        assertTrue(ran, "injected processor must have run")
        assertTrue(root.width > 0 && root.height > 0, "layout must still have completed")
    }
}
