package dev.groknull.bpmner.core

import com.fasterxml.jackson.annotation.JsonClassDescription

enum class BpmnDiagnosticSource {
    GRAPH,
    RENDER,
    XSD,
    LINT,
}

enum class BpmnRepairScope {
    OUTLINE,
    PHASE,
    COMPOSITION,
    LAYOUT,
    FULL_PROCESS,
}

enum class RepairKind {
    LOCAL_MODEL_FIX,
    LOCAL_XML_FIX,
    LLM_MODEL_PATCH,
    LLM_XML_REWRITE,
    UNFIXABLE,
    ;

    fun isLocal(): Boolean = this == LOCAL_MODEL_FIX || this == LOCAL_XML_FIX

    fun isLlm(): Boolean = this == LLM_MODEL_PATCH || this == LLM_XML_REWRITE
}

enum class BpmnRepairSafety { SAFE_AUTOMATIC, SAFE_MANUAL, LLM_ONLY }

@JsonClassDescription(
    "Normalized BPMN validation or rendering diagnostic linked back to the typed definition where possible",
)
data class BpmnDiagnostic(
    val source: BpmnDiagnosticSource,
    val message: String,
    val rule: String? = null,
    val category: String? = null,
    val elementId: String? = null,
    val objectRef: String? = null,
    val repairScope: BpmnRepairScope? = null,
    val ownerRef: String? = null,
    val kind: RepairKind? = null,
    val repairSafety: BpmnRepairSafety? = null,
    val fixHandler: String? = null,
)

data class GlobalDiagnostics(
    val diagnostics: List<BpmnDiagnostic>,
) {
    fun countFor(source: BpmnDiagnosticSource): Int = diagnostics.count { it.source == source }
}

fun BpmnDiagnostic.format(): String =
    buildString {
        append("source=${source.name.lowercase()}")
        rule?.let { append(", rule=$it") }
        category?.let { append(", category=$it") }
        elementId?.let { append(", elementId=$it") }
        objectRef?.let { append(", objectRef=$it") }
        repairScope?.let { append(", repairScope=${it.name.lowercase()}") }
        ownerRef?.let { append(", owner=$it") }
        kind?.let { append(", kind=${it.name.lowercase()}") }
        append(": $message")
    }
