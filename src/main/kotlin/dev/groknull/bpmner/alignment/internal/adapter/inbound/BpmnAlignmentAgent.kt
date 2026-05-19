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
import dev.groknull.bpmner.alignment.AlignedBpmnXml
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentCheckedEvent
import dev.groknull.bpmner.alignment.BpmnAlignmentException
import dev.groknull.bpmner.alignment.internal.domain.BpmnAlignmentPostChecker
import dev.groknull.bpmner.alignment.internal.domain.BpmnSummarizer
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.validation.FinalValidatedBpmnXml
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.context.ApplicationEventPublisher

@PrimaryAdapter
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
    @Action(description = "Verify semantic alignment between process contract and generated BPMN")
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

        val findings =
            promptRunner.createObject(
                promptFactory.prompt(request, contract.contract, summary),
                AlignmentFindings::class.java,
            ) ?: AlignmentFindings(
                issues = emptyList(),
                rationale = "Alignment model failed to produce a structured report.",
            )

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
}
