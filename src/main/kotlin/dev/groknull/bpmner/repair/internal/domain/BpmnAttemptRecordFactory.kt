/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.conformance.BpmnFingerprintService
import dev.groknull.bpmner.repair.BpmnAttemptRecord
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import org.springframework.stereotype.Component

@Component
internal class BpmnAttemptRecordFactory(
    private val fingerprints: BpmnFingerprintService,
) {
    fun toRecord(
        attempt: BpmnRepairAttempt,
        repairPromptFingerprint: String? = null,
    ): BpmnAttemptRecord {
        val globalDiagnostics = attempt.evaluation.globalDiagnostics
        return BpmnAttemptRecord(
            attemptNumber = attempt.attemptNumber,
            repairAttempts = attempt.repairAttempts,
            graphDiagnostics = globalDiagnostics.countFor(BpmnDiagnosticSource.GRAPH),
            renderDiagnostics = globalDiagnostics.countFor(BpmnDiagnosticSource.RENDER),
            xsdDiagnostics = globalDiagnostics.countFor(BpmnDiagnosticSource.XSD),
            lintDiagnostics = globalDiagnostics.countFor(BpmnDiagnosticSource.LINT),
            diagnosticFingerprint = fingerprints.diagnosticFingerprint(attempt.diagnostics),
            blockingDiagnosticFingerprint = fingerprints.blockingDiagnosticFingerprint(attempt.diagnostics),
            definitionFingerprint = fingerprints.definitionFingerprint(attempt.definition),
            repairPromptFingerprint = repairPromptFingerprint,
            topDiagnostics = attempt.diagnostics.take(TOP_DIAGNOSTICS_LIMIT).map { formatTopDiagnostic(it) },
        )
    }

    private fun formatTopDiagnostic(diagnostic: BpmnDiagnostic): String = buildString {
        append(diagnostic.source.name.lowercase())
        diagnostic.rule?.let { append(" [$it]") }
        diagnostic.elementId?.let { append(" @$it") }
        append(": ${diagnostic.message.take(DIAGNOSTIC_MESSAGE_PREVIEW_LENGTH)}")
    }

    companion object {
        private const val TOP_DIAGNOSTICS_LIMIT = 5
        private const val DIAGNOSTIC_MESSAGE_PREVIEW_LENGTH = 120
    }
}
