/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.domain.BpmnDefinition
import dev.groknull.bpmner.domain.BpmnRequest
import dev.groknull.bpmner.validation.BpmnDiagnostic
import jakarta.validation.Valid

// --- Outline and phase types (intermediate stages within generation) ---

data class ProcessOutline(
    val request: BpmnRequest,
    @field:Valid
    val definition: BpmnDefinition,
    @field:Valid
    val metrics: OutlineMetrics,
)

data class OutlineMetrics(
    val phaseCount: Int,
    val exclusiveBranchCount: Int,
    val inclusiveBranchCount: Int,
    val parallelBranchCount: Int,
    val loopCount: Int,
    val subprocessCount: Int,
)

data class ValidatedOutline(
    val outline: ProcessOutline,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
    val fidelityReport: BpmnFidelityReport = BpmnFidelityReport.VALID,
) {
    val definition: BpmnDefinition
        get() = outline.definition
}
