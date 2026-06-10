/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.springframework.stereotype.Component

@Component
internal class BpmnContractPromptFactory(
    private val config: BpmnConfig,
) {
    fun templateModel(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): Map<String, Any> = mapOf(
        "maxAssumptions" to config.contract.maxAssumptions,
        "rationale" to assessment.rationale,
        "missingAreas" to assessment.missingAreas.map { it.name },
        "evidence" to assessment.evidence.map {
            mapOf("id" to it.id, "text" to it.text)
        },
        "clarificationHistory" to request.clarificationHistory.map {
            mapOf(
                "questionId" to it.questionId,
                "questionText" to it.questionText,
                "answerText" to it.answerText,
            )
        },
        "styleGuide" to (request.styleGuide ?: ""),
        "processDescription" to request.processDescription,
    )

    fun extractionExamples(): List<Pair<String, FlatProcessContract>> = listOf(
        ContractExtractionExamples.MESSAGE_END_LABEL to ContractExtractionExamples.messageEndExample,
        ContractExtractionExamples.ESCALATION_END_LABEL to ContractExtractionExamples.escalationEndExample,
        ContractExtractionExamples.SEND_TASK_LABEL to ContractExtractionExamples.sendTaskExample,
        ContractExtractionExamples.INTERMEDIATE_THROW_LABEL to ContractExtractionExamples.intermediateThrowExample,
        ContractExtractionExamples.SEND_THEN_NORMAL_LABEL to ContractExtractionExamples.sendThenNormalExample,
        ContractExtractionExamples.INCLUSIVE_GATEWAY_LABEL to ContractExtractionExamples.inclusiveGatewayExample,
        ContractExtractionExamples.BUSINESS_RULE_TASK_LABEL to ContractExtractionExamples.businessRuleTaskExample,
        ContractExtractionExamples.SUB_PROCESS_LABEL to ContractExtractionExamples.subProcessExample,
        ContractExtractionExamples.EVENT_SUB_PROCESS_LABEL to ContractExtractionExamples.eventSubProcessExample,
    )
}
