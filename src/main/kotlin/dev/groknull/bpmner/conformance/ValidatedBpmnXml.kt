/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import dev.groknull.bpmner.bpmn.BpmnDefinition

data class ValidatedBpmnXml(
    val definition: BpmnDefinition,
    val xml: String,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
    val repairAttempts: Int = 0,
)

data class FinalValidatedBpmnXml(
    val definition: BpmnDefinition,
    val xml: String,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
)
