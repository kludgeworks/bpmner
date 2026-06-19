/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.domain.BpmnDefinition
import dev.groknull.bpmner.domain.LaidOutProcessGraph
import dev.groknull.bpmner.domain.RenderedBpmn
import org.jmolecules.architecture.hexagonal.PrimaryPort

@PrimaryPort
interface BpmnValidator {
    fun evaluate(
        graph: LaidOutProcessGraph,
        definition: BpmnDefinition,
        rendered: RenderedBpmn?,
        renderFailureMessage: String? = null,
        repairAttempts: Int,
    ): BpmnEvaluation

    fun logDiagnosticSummary(diagnostics: List<BpmnDiagnostic>)
}
