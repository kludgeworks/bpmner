/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.domain

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnRequest
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
