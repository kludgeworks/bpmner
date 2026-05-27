/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.core.ActionRetryPolicy
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import dev.groknull.bpmner.alignment.AlignedBpmnXml
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentCheckedEvent
import dev.groknull.bpmner.alignment.BpmnAlignmentException
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.alignment.internal.domain.BpmnAlignmentPostChecker
import dev.groknull.bpmner.alignment.internal.domain.BpmnSummarizer
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.validation.FinalValidatedBpmnXml
import org.jmolecules.architecture.hexagonal.Application
import org.springframework.context.ApplicationEventPublisher

@Application
@Agent(description = "Verify semantic alignment between process contract and generated BPMN")
internal class BpmnAlignmentAgent(
    private val config: BpmnConfig,
    private val summarizer: BpmnSummarizer,
    private val postChecker: BpmnAlignmentPostChecker,
    private val promptFactory: BpmnAlignmentPromptFactory,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @AchievesGoal(
        description = "Verify semantic alignment between process contract and generated BPMN",
        export =
        Export(
            name = "checkAlignment",
            remote = true,
            startingInputTypes = [
                BpmnRequest::class,
                ValidatedProcessContract::class,
                FinalValidatedBpmnXml::class,
            ],
        ),
    )
    @Action(
        description = "Verify semantic alignment between process contract and generated BPMN",
        actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE,
    )
    fun checkAlignment(
        request: BpmnRequest,
        contract: ValidatedProcessContract,
        bpmn: FinalValidatedBpmnXml,
        context: OperationContext,
    ): AlignedBpmnXml {
        val summary = summarizer.summarize(bpmn.definition)
        val promptRunner =
            config.alignmentValidator
                .promptRunner(context)
                .withPromptContributor(request)
        val findings = requestAlignmentFindings(promptRunner, request, contract.contract, summary)

        val report = postChecker.apply(findings, summary)
        eventPublisher.publishEvent(BpmnAlignmentCheckedEvent(request, report))

        if (report.verdict == AlignmentVerdict.FAILED) {
            throw BpmnAlignmentException(
                message = "Generated BPMN does not align with process contract: ${report.rationale}",
                report = report,
            )
        }

        return AlignedBpmnXml(xml = bpmn.xml, alignmentReport = report)
    }

    /**
     * Phase 5 (#220): `createObject` returns non-null per Embabel's contract; null-defences here
     * previously smuggled "model didn't respond" through a synthetic AlignmentIssue. Translate the
     * framework's typed exceptions at this seam so the failure type stays legible —
     * [BpmnAlignmentException] with `report = null` means "the alignment model failed," not
     * "the model examined the BPMN and found problems." Extracted from [checkAlignment] so detekt's
     * `ThrowsCount` discipline holds at the action method.
     */
    private fun requestAlignmentFindings(
        promptRunner: PromptRunner,
        request: BpmnRequest,
        contract: ProcessContract,
        summary: BpmnDefinitionSummary,
    ): AlignmentFindings = try {
        promptRunner.createObject(
            promptFactory.prompt(request, contract, summary),
            AlignmentFindings::class.java,
        )
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
}
