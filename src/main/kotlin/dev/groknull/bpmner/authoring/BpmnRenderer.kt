/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnRenderer {
    fun render(definition: BpmnDefinition): RenderedBpmn

    fun render(graph: LaidOutProcessGraph): RenderedBpmn
}
