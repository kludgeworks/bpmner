/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnRequest
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
    val branchCount: Int,
    val loopCount: Int,
    val subprocessCount: Int,
)

data class ValidatedOutline(
    val outline: ProcessOutline,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
) {
    val definition: BpmnDefinition
        get() = outline.definition
}

data class PhasePlan(
    val phaseId: String,
    val ownerRef: String,
    @field:Valid
    val definition: BpmnDefinition,
)

data class PhasePlanSet(
    val outline: ValidatedOutline,
    val phasePlans: List<PhasePlan>,
)

data class ValidatedPhasePlan(
    val phaseId: String,
    val ownerRef: String,
    @field:Valid
    val definition: BpmnDefinition,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
)

data class ValidatedPhasePlanSet(
    val outline: ValidatedOutline,
    val phasePlans: List<ValidatedPhasePlan>,
) {
    val definition: BpmnDefinition
        get() = phasePlans.single().definition
}
