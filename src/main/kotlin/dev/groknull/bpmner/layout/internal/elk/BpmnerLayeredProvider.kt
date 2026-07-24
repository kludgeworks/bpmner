/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.elk

import org.eclipse.elk.alg.layered.ElkLayered
import org.eclipse.elk.alg.layered.graph.transform.ElkGraphTransformer
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.AbstractLayoutProvider
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.util.IElkProgressMonitor
import org.eclipse.elk.graph.ElkNode

/** Algorithm id under which [BpmnerLayeredProvider] is registered with ELK. */
internal const val BPMNER_LAYERED_ALGORITHM_ID = "bpmner.layered"

/**
 * Custom-registered ELK algorithm ([BPMNER_LAYERED_ALGORITHM_ID]) wrapping the stock layered
 * algorithm. With an empty injection list (production, for now — the smallest real consumer is
 * proven only in tests) it calls the exact same public [ElkLayered] entry points stock
 * `LayeredLayoutProvider` does, so production output is trivially identical to stock `layered`.
 *
 * A non-empty [processorInjections] instead drives [ElkLayered] through its public white-box test
 * API ([ElkLayered.prepareLayoutTest] / [ElkLayered.runLayoutTestUntil] /
 * [ElkLayered.runLayoutTestStep]), splicing each declared processor in immediately after its named
 * stock processor finishes. That API only supports flat (non-hierarchical) graphs (hierarchical
 * graphs need every nested `LGraph` configured before crossing minimization sweeps the whole
 * hierarchy, which only [ElkLayered.doCompoundLayout] does) — a documented gap this carrier does
 * not yet need to close, since production never injects.
 *
 * `LGraph` is the only ELK "internal" (`alg.layered`) type this carrier touches, and only via
 * `ElkLayered`'s own public API — never `InternalProperties` or `GraphConfigurator` directly,
 * which are the less-stable surfaces AD-622-01 flags for confinement.
 *
 * The no-arg constructor (via `@JvmOverloads`) is what ELK's `AlgorithmFactory` reflectively
 * instantiates for the registered production algorithm; the injection list is otherwise supplied
 * directly by callers (e.g. the in-slot processor test) that construct this provider themselves.
 */
internal class BpmnerLayeredProvider @JvmOverloads constructor(
    private val processorInjections: List<ProcessorInjection> = emptyList(),
) : AbstractLayoutProvider() {
    override fun layout(graph: ElkNode, progressMonitor: IElkProgressMonitor) {
        val transformer = ElkGraphTransformer()
        val lgraph = transformer.importGraph(graph)
        val elkLayered = ElkLayered()
        val hierarchical = graph.getProperty(LayeredOptions.HIERARCHY_HANDLING) == HierarchyHandling.INCLUDE_CHILDREN

        if (processorInjections.isEmpty()) {
            if (hierarchical) {
                elkLayered.doCompoundLayout(lgraph, progressMonitor)
            } else {
                elkLayered.doLayout(lgraph, progressMonitor)
            }
        } else {
            check(!hierarchical) {
                "bpmner.layered processor injection is not yet supported on hierarchical graphs"
            }
            val state = elkLayered.prepareLayoutTest(lgraph)
            for (injection in processorInjections) {
                elkLayered.runLayoutTestUntil(injection.runAfter, true, state)
                for (component in state.graphs) {
                    injection.processor.process(component, progressMonitor)
                }
            }
            while (!elkLayered.isLayoutTestFinished(state)) {
                elkLayered.runLayoutTestStep(state)
            }
        }

        if (!progressMonitor.isCanceled) {
            transformer.applyLayout(lgraph)
        }
    }
}
