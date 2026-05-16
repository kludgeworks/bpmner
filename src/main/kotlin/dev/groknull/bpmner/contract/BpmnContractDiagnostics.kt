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

package dev.groknull.bpmner.contract

import com.fasterxml.jackson.annotation.JsonClassDescription

enum class ContractIssueSeverity {
    ERROR,
    WARNING,
}

enum class ContractValidationCode {
    MISSING_PROCESS_NAME,
    MISSING_TRIGGER,
    TRIGGER_WITHOUT_TRACE,
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

data class ValidatedProcessContract(
    val contract: ProcessContract,
    val report: ContractValidationReport,
) {
    val isValid: Boolean = report.isValid
}

fun ContractValidationIssue.format(): String =
    buildString {
        append("code=${code.name.lowercase()}")
        append(", severity=${severity.name.lowercase()}")
        targetId?.let { append(", targetId=$it") }
        if (evidenceIds.isNotEmpty()) append(", evidenceIds=${evidenceIds.joinToString(",")}")
        append(": $message")
    }
