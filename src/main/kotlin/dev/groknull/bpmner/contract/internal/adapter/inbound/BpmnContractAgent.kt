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

package dev.groknull.bpmner.contract.internal.adapter.inbound

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import dev.groknull.bpmner.contract.ContractIssueSeverity
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.contract.format
import dev.groknull.bpmner.contract.internal.domain.BpmnContractValidator
import dev.groknull.bpmner.contract.internal.domain.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
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
}
