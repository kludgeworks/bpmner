package dev.groknull.bpmner.core

import com.embabel.chat.Message

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
    val definitionFingerprint: String,
    val repairPromptFingerprint: String?,
    val topDiagnostics: List<String> = emptyList(),
) {
    fun compact(): String =
        "#$attemptNumber(repairs=$repairAttempts,graph=$graphDiagnostics,render=$renderDiagnostics," +
            "xsd=$xsdDiagnostics,lint=$lintDiagnostics,diag=$diagnosticFingerprint," +
            "def=$definitionFingerprint,prompt=${repairPromptFingerprint ?: "-"})"
}

class BpmnValidatorInfrastructureException(
    message: String,
) : IllegalStateException(message)

class BpmnRefinementFailureException(
    message: String,
    val summary: String,
) : IllegalStateException(message)
