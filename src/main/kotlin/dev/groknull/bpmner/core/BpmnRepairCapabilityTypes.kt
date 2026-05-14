package dev.groknull.bpmner.core

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

data class BpmnLintRuleCapability(
    val id: String,
    val kind: RepairKind,
    val repairSafety: BpmnRepairSafety,
    val fixHandler: String?,
    val handlerExists: Boolean,
    val replacementMap: Map<String, String>?,
    val layoutSensitive: Boolean = false,
)

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
