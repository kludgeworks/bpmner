/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnDefinition

/** Result of [BpmnContractConformancePort.conform]: the stamped [definition] plus what changed. */
data class BpmnConformance(val definition: BpmnDefinition, val corrections: List<ContractCorrection>)

/**
 * One contract-determined attribute the conformance pass corrected. Severity-free by design
 * (ADR-685-25): a correction is never a failure, only a record of what the pass overwrote.
 */
data class ContractCorrection(
    val elementId: String,
    val field: String,
    val modelValue: String?,
    val contractValue: String,
)
