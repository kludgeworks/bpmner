/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.internal.model.RenderedBpmn
import dev.groknull.bpmner.validation.ValidatedBpmnXml

data class BpmnEvaluation(
    val definition: BpmnDefinition,
    val rendered: RenderedBpmn?,
    val diagnostics: List<BpmnDiagnostic>,
    val globalDiagnostics: GlobalDiagnostics,
    val validatedXml: String?,
    val renderFailureMessage: String? = null,
    val rawLintIssues: List<LintIssue>? = null,
) {
    /**
     * The evaluation is successful when the document was validated AND no *blocking* (ERROR)
     * diagnostics remain. Advisory diagnostics (WARNING / INFO) are surfaced through the
     * `diagnostics` list and may persist in a successful outcome — matching bpmnlint's
     * native severity model.
     */
    fun isSuccessful(): Boolean = validatedXml != null && blockingDiagnostics.isEmpty()

    /** ERROR-severity diagnostics — the ones the refinement engine must drive to zero. */
    val blockingDiagnostics: List<BpmnDiagnostic>
        get() = diagnostics.filter { it.isBlocking }

    /** WARNING / INFO diagnostics — surfaced in the final report but not blocking. */
    val advisoryDiagnostics: List<BpmnDiagnostic>
        get() = diagnostics.filterNot { it.isBlocking }

    fun toValidatedBpmnXml(repairAttempts: Int): ValidatedBpmnXml = ValidatedBpmnXml(
        definition = definition,
        xml = validatedXml ?: error("No validated BPMN XML available"),
        diagnostics = diagnostics,
        repairAttempts = repairAttempts,
    )
}

class BpmnValidatorInfrastructureException(
    message: String,
) : IllegalStateException(message)
