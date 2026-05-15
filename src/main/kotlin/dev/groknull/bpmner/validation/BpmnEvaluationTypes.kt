package dev.groknull.bpmner.validation

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.RenderedBpmn
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
    fun isSuccessful(): Boolean = validatedXml != null && diagnostics.isEmpty()

    fun toValidatedBpmnXml(repairAttempts: Int): ValidatedBpmnXml =
        ValidatedBpmnXml(
            definition = definition,
            xml = validatedXml ?: error("No validated BPMN XML available"),
            diagnostics = diagnostics,
            repairAttempts = repairAttempts,
        )
}

class BpmnValidatorInfrastructureException(
    message: String,
) : IllegalStateException(message)
