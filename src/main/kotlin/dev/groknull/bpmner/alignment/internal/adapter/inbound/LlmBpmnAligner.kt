/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment.internal.adapter.inbound

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.alignment.BpmnAligner
import dev.groknull.bpmner.alignment.BpmnAlignmentCheckedEvent
import dev.groknull.bpmner.alignment.BpmnAlignmentException
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.alignment.internal.domain.BpmnAlignmentPostChecker
import dev.groknull.bpmner.alignment.internal.domain.BpmnSummarizer
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.styleGuideContribution
import dev.groknull.bpmner.config.BpmnConfig
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.validation.FinalValidatedBpmnXml
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@PrimaryAdapter
@Component
internal class LlmBpmnAligner(
    private val config: BpmnConfig,
    private val summarizer: BpmnSummarizer,
    private val postChecker: BpmnAlignmentPostChecker,
    private val contractRenderer: ProcessContractMarkdownRenderer,
    private val eventPublisher: ApplicationEventPublisher,
) : BpmnAligner {
    override fun align(
        ready: ReadyBpmnContext,
        contract: ValidatedProcessContract,
        bpmn: FinalValidatedBpmnXml,
        context: OperationContext,
    ): BpmnAlignmentReport {
        val request = ready.request
        val summary = summarizer.summarize(bpmn.definition)
        val promptRunner =
            config.alignmentValidator
                .promptRunner(context)
                .withPromptContributor(PromptContributor.fixed(request.styleGuideContribution()))
        val findings = requestAlignmentFindings(promptRunner, request, contract.contract, summary)

        val report = postChecker.apply(findings, summary)
        eventPublisher.publishEvent(BpmnAlignmentCheckedEvent(request, report))

        return report
    }

    /**
     * Translates the framework's typed exceptions at this seam so the failure type stays legible —
     * [BpmnAlignmentException] with `report = null` means "the alignment model failed," not
     * "the model examined the BPMN and found problems." Extracted from [align] so detekt's
     * `ThrowsCount` discipline holds at the action method.
     */
    private fun requestAlignmentFindings(
        promptRunner: PromptRunner,
        request: BpmnRequest,
        contract: ProcessContract,
        summary: BpmnDefinitionSummary,
    ): AlignmentFindings = try {
        val result = promptRunner
            .creating(AlignmentFindings::class.java)
            .fromTemplate("bpmner/check_alignment", templateModel(request, contract, summary))
        result
    } catch (e: InvalidLlmReturnFormatException) {
        throw BpmnAlignmentException(
            message = "Alignment model failed to produce a structured report: ${e.message}",
            report = null,
            cause = e,
        )
    } catch (e: InvalidLlmReturnTypeException) {
        throw BpmnAlignmentException(
            message = "Alignment model returned an invalid AlignmentFindings: ${e.message}",
            report = null,
            cause = e,
        )
    }

    private fun templateModel(
        request: BpmnRequest,
        contract: ProcessContract,
        summary: BpmnDefinitionSummary,
    ): Map<String, Any> = mapOf(
        "contractMarkdown" to contractRenderer.render(contract).trim(),
        "processId" to summary.processId,
        "processName" to summary.processName,
        "elementLines" to summary.elements.map { element ->
            "[${element.id}] ${element.type}: ${element.name ?: "(unnamed)"}"
        },
        "flowLines" to summary.flows.map { flow ->
            val condition = flow.conditionExpression?.let { " [if $it]" } ?: ""
            val name = flow.name?.let { " ($it)" } ?: ""
            "[${flow.id}] ${flow.sourceRef} → ${flow.targetRef}$condition$name"
        },
        "unreachableElementIds" to summary.unreachableElementIds,
        "processDescription" to request.processDescription,
    )
}
