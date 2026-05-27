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
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.contract.format
import dev.groknull.bpmner.contract.internal.domain.BpmnContractValidator
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.jmolecules.architecture.hexagonal.Application
import org.slf4j.LoggerFactory

@Application
@Agent(description = "Extract a source-grounded process contract from natural-language input")
internal class BpmnContractAgent(
    private val config: BpmnConfig,
    private val validator: BpmnContractValidator,
    private val markdownRenderer: ProcessContractMarkdownRenderer,
) {
    private val promptFactory = BpmnContractPromptFactory(config.contract)
    private val logger = LoggerFactory.getLogger(BpmnContractAgent::class.java)

    @AchievesGoal(
        description = "Extract a source-grounded process contract from natural-language input",
        export =
        Export(
            name = "extractProcessContract",
            remote = true,
            startingInputTypes = [
                BpmnRequest::class,
                ProcessInputAssessment::class,
            ],
        ),
    )
    @Action(
        description = "Extract a source-grounded process contract from a BPMN request and readiness assessment",
    )
    fun extractProcessContract(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
        context: OperationContext,
    ): ValidatedProcessContract {
        val promptRunner =
            config.contractExtractor
                .promptRunner(context)
                .withPromptContributor(request)

        // Phase 5 (#220): `createObject` returns non-null per Embabel's contract; it throws on
        // failure. Let the typed exception propagate to the planner — no defense needed here.
        val contract =
            promptRunner.createObject(
                promptFactory.prompt(request, assessment, clarificationHistory = request.clarificationHistory),
                ProcessContract::class.java,
            )

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
}
