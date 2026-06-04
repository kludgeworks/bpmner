/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.OperationContext
import dev.groknull.bpmner.contract.ContractIssueSeverity
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.contract.format
import dev.groknull.bpmner.contract.internal.domain.BpmnContractValidator
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import org.jmolecules.architecture.hexagonal.Application
import org.slf4j.LoggerFactory

@Application
@Agent(description = "Extract a source-grounded process contract from natural-language input")
internal class BpmnContractAgent(
    private val config: BpmnConfig,
    private val validator: BpmnContractValidator,
    private val markdownRenderer: ProcessContractMarkdownRenderer,
) {
    private val logger = LoggerFactory.getLogger(BpmnContractAgent::class.java)

    @AchievesGoal(
        description = "Extract a source-grounded process contract from natural-language input",
        export =
        Export(
            name = "extractProcessContract",
            remote = true,
            startingInputTypes = [
                ReadyBpmnContext::class,
            ],
        ),
    )
    @Action(
        description = "Extract a source-grounded process contract from a BPMN request and readiness assessment",
    )
    fun extractProcessContract(
        ready: ReadyBpmnContext,
        context: OperationContext,
    ): ValidatedProcessContract {
        val request = ready.request
        val assessment = ready.assessment
        val promptRunner =
            config.contractExtractor
                .promptRunner(context)
                .withPromptContributor(request)

        val flat = promptRunner
            .creating(FlatProcessContract::class.java)
            // Typed few-shot examples for the five discrimination boundaries GPT-4.1
            // does not reliably reproduce from keyword descriptions alone (see ContractExtractionExamples).
            .withExample(ContractExtractionExamples.MESSAGE_END_LABEL, ContractExtractionExamples.messageEndExample)
            .withExample(ContractExtractionExamples.ESCALATION_END_LABEL, ContractExtractionExamples.escalationEndExample)
            .withExample(ContractExtractionExamples.SEND_TASK_LABEL, ContractExtractionExamples.sendTaskExample)
            .withExample(ContractExtractionExamples.INTERMEDIATE_THROW_LABEL, ContractExtractionExamples.intermediateThrowExample)
            .withExample(ContractExtractionExamples.SEND_THEN_NORMAL_LABEL, ContractExtractionExamples.sendThenNormalExample)
            .withExample(ContractExtractionExamples.INCLUSIVE_GATEWAY_LABEL, ContractExtractionExamples.inclusiveGatewayExample)
            .withExample(ContractExtractionExamples.BUSINESS_RULE_TASK_LABEL, ContractExtractionExamples.businessRuleTaskExample)
            .withExample(ContractExtractionExamples.SUB_PROCESS_LABEL, ContractExtractionExamples.subProcessExample)
            .fromTemplate("bpmner/extract_contract", templateModel(request, assessment))
        val contract = flat.toSealed()

        logger.info("Contract extracted:\n{}", markdownRenderer.render(contract))
        val report = validator.validate(contract)
        if (!report.isValid) {
            val errorCount = report.issues.count { it.severity == ContractIssueSeverity.ERROR }
            logger.warn(
                "Contract validation found {} error(s): {}",
                errorCount,
                report.issues.joinToString { it.format() },
            )
        }
        return ValidatedProcessContract(contract = contract, report = report)
    }

    private fun templateModel(
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
}
