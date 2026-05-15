package dev.groknull.bpmner.contract.internal.adapter.inbound

import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.core.BpmnContractConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.readiness.ProcessInputAssessment

internal class BpmnContractPromptFactory(
    private val config: BpmnContractConfig,
) {
    fun prompt(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
        clarificationHistory: List<ClarificationExchange>,
    ): String =
        buildString {
            appendLine("Return only a structured ${ProcessContract::class.simpleName} object.")
            appendLine()
            appendLine("Extract a source-grounded process contract from the supplied inputs.")
            appendLine("Do not invent actors, triggers, end states, decisions, branches, or artifacts.")
            appendLine(
                "If a fact required for a complete contract is not present in the source text or" +
                    " clarification answers, record it as a ContractAssumption with at least one TraceLink.",
            )
            appendLine("Cap assumptions at ${config.maxAssumptions}.")
            appendLine()
            appendLine("Traceability rules:")
            appendLine(
                "- Every ContractActivity, ContractDecision, ContractEndState, and ContractAssumption" +
                    " must carry at least one TraceLink.",
            )
            appendLine(
                "- TraceLink.sourceId must reference an assessment evidence id, a clarification" +
                    " questionId, or a literal input-text marker.",
            )
            appendLine("- ContractBranch must have a non-blank label and a condition when applicable.")
            appendLine()
            appendLine("Readiness assessment rationale:")
            appendLine(assessment.rationale)
            if (assessment.missingAreas.isNotEmpty()) {
                appendLine()
                appendLine("Missing process areas to surface as assumptions or clarifications:")
                assessment.missingAreas.forEach { appendLine("- ${it.name}") }
            }
            if (assessment.evidence.isNotEmpty()) {
                appendLine()
                appendLine("Assessment evidence ids available for TraceLink.sourceId:")
                assessment.evidence.forEach { appendLine("- ${it.id}: ${it.text}") }
            }
            if (clarificationHistory.isNotEmpty()) {
                appendLine()
                appendLine("Clarification answers (in order). Use questionId as TraceLink.sourceId when relevant:")
                clarificationHistory.forEach {
                    appendLine("- [${it.questionId}] Q: ${it.questionText}")
                    appendLine("  A: ${it.answerText}")
                }
            }
            if (!request.styleGuide.isNullOrBlank()) {
                appendLine()
                appendLine("Style guide (constraints on naming and structure):")
                appendLine(request.styleGuide)
            }
            appendLine()
            appendLine("Original BPMN request text:")
            appendLine(request.processDescription)
        }
}
