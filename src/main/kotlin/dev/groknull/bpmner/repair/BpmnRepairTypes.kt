/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair

import com.embabel.chat.Message
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnEvaluation

data class BpmnRepairAttempt(
    val attemptNumber: Int,
    val repairAttempts: Int,
    val graph: LaidOutProcessGraph,
    val evaluation: BpmnEvaluation,
    val messages: List<Message>,
) {
    val definition: BpmnDefinition
        get() = evaluation.definition

    val diagnostics: List<BpmnDiagnostic>
        get() = evaluation.diagnostics
}

data class BpmnAttemptHistory(
    val records: List<BpmnAttemptRecord> = emptyList(),
) {
    val size: Int get() = records.size
    val last: BpmnAttemptRecord? get() = records.lastOrNull()

    fun append(record: BpmnAttemptRecord): BpmnAttemptHistory = copy(records = records + record)

    fun containsDefinitionFingerprint(fingerprint: String): Boolean = records.any { it.definitionFingerprint == fingerprint }

    fun compact(): String = records.joinToString(" | ") { it.compact() }
}

data class BpmnAttemptRecord(
    val attemptNumber: Int,
    val repairAttempts: Int,
    val graphDiagnostics: Int,
    val renderDiagnostics: Int,
    val xsdDiagnostics: Int,
    val lintDiagnostics: Int,
    val diagnosticFingerprint: String,
    // Fingerprint over only the blocking (ERROR) diagnostics. Used by the refinement engine's
    // "stuck blocking" guard so that advisory-warning oscillation between iterations doesn't
    // mask a permanently-stuck blocking error.
    val blockingDiagnosticFingerprint: String,
    val definitionFingerprint: String,
    val repairPromptFingerprint: String?,
    val topDiagnostics: List<String> = emptyList(),
) {
    fun compact(): String =
        "#$attemptNumber(repairs=$repairAttempts,graph=$graphDiagnostics,render=$renderDiagnostics," +
            "xsd=$xsdDiagnostics,lint=$lintDiagnostics,diag=$diagnosticFingerprint," +
            "def=$definitionFingerprint,prompt=${repairPromptFingerprint ?: "-"})"
}

class BpmnRefinementFailureException(
    message: String,
    val summary: String,
) : IllegalStateException(message)
