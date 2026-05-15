package dev.groknull.bpmner.contract.internal.adapter.inbound

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.internal.domain.BpmnContractValidator
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ContractIssueSeverity
import dev.groknull.bpmner.core.ProcessContract
import dev.groknull.bpmner.core.ProcessInputAssessment
import dev.groknull.bpmner.core.ValidatedProcessContract
import dev.groknull.bpmner.core.format
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory

@PrimaryAdapter
@Agent(description = "Extract a source-grounded process contract from natural-language input")
internal class BpmnContractAgent(
    private val config: BpmnConfig,
    private val validator: BpmnContractValidator,
    private val markdownRenderer: ProcessContractMarkdownRenderer,
) {
    private val promptFactory = BpmnContractPromptFactory(config.contract)
    private val logger = LoggerFactory.getLogger(BpmnContractAgent::class.java)

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

    @Action(description = "Unwrap the process contract from a validated contract")
    fun unwrapProcessContract(validated: ValidatedProcessContract): ProcessContract = validated.contract
}
