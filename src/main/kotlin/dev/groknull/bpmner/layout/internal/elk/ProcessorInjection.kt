/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.elk

import org.eclipse.elk.alg.layered.graph.LGraph
import org.eclipse.elk.core.alg.ILayoutProcessor

/**
 * One BPMN-declared processor to run, on every connected component of the graph, immediately
 * after the named stock ELK processor has finished executing.
 */
internal data class ProcessorInjection(
    val runAfter: Class<out ILayoutProcessor<LGraph>>,
    val processor: ILayoutProcessor<LGraph>,
)
