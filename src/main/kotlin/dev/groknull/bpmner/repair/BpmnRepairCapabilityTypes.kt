/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair

import dev.groknull.bpmner.validation.BpmnDiagnostic

data class BpmnLocalFixFailure(
    val rule: String,
    val elementId: String?,
    val reason: String,
)

data class BpmnLocalFixSummary(
    val modelApplied: Int,
    val xmlApplied: Int,
    val xmlErrors: Int,
) {
    val total: Int get() = modelApplied + xmlApplied

    companion object {
        val EMPTY = BpmnLocalFixSummary(modelApplied = 0, xmlApplied = 0, xmlErrors = 0)
    }
}

data class BpmnLocalRepairOutcome(
    val failures: List<BpmnLocalFixFailure>,
) {
    fun matches(diagnostic: BpmnDiagnostic): BpmnLocalFixFailure? =
        failures.firstOrNull { it.rule == diagnostic.rule && it.elementId == diagnostic.elementId }

    companion object {
        val EMPTY = BpmnLocalRepairOutcome(emptyList())
    }
}
