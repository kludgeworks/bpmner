package dev.groknull.bpmner.core

import com.fasterxml.jackson.annotation.JsonClassDescription

enum class ContractIssueSeverity {
    ERROR,
    WARNING,
}

enum class ContractValidationCode {
    MISSING_PROCESS_NAME,
    MISSING_TRIGGER,
    NO_END_STATE,
    INSUFFICIENT_ACTIVITIES,
    DECISION_BRANCH_TOO_FEW,
    BRANCH_WITHOUT_CONDITION_OR_LABEL,
    ASSUMPTION_WITHOUT_TRACE,
    CONTRACT_ITEM_WITHOUT_TRACE,
}

@JsonClassDescription("Structural validation issue raised against an extracted ProcessContract")
data class ContractValidationIssue(
    val code: ContractValidationCode,
    val severity: ContractIssueSeverity,
    val message: String,
    val targetId: String? = null,
    val evidenceIds: List<String> = emptyList(),
)

data class ContractValidationReport(
    val issues: List<ContractValidationIssue>,
) {
    val isValid: Boolean = issues.none { it.severity == ContractIssueSeverity.ERROR }
}

fun ContractValidationIssue.format(): String =
    buildString {
        append("code=${code.name.lowercase()}")
        append(", severity=${severity.name.lowercase()}")
        targetId?.let { append(", targetId=$it") }
        if (evidenceIds.isNotEmpty()) append(", evidenceIds=${evidenceIds.joinToString(",")}")
        append(": $message")
    }
