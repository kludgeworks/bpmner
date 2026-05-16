/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.repair.BpmnAttemptRecord
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnFingerprintService
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
            definitionFingerprint = fingerprints.definitionFingerprint(attempt.definition),
            repairPromptFingerprint = repairPromptFingerprint,
            topDiagnostics = attempt.diagnostics.take(TOP_DIAGNOSTICS_LIMIT).map { formatTopDiagnostic(it) },
        )
    }

    private fun formatTopDiagnostic(diagnostic: BpmnDiagnostic): String =
        buildString {
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
