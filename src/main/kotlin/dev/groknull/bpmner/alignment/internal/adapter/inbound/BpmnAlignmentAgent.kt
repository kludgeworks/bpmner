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

package dev.groknull.bpmner.alignment.internal.adapter.inbound

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import dev.groknull.bpmner.alignment.AlignedBpmnXml
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentCheckedEvent
import dev.groknull.bpmner.alignment.BpmnAlignmentException
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.alignment.internal.domain.BpmnAlignmentPostChecker
import dev.groknull.bpmner.alignment.internal.domain.BpmnSummarizer
import dev.groknull.bpmner.contract.ProcessContract
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
    @Action(description = "Check if generated BPMN aligns with the process contract")
    fun checkAlignment(
        request: BpmnRequest,
        contract: ProcessContract,
        bpmn: FinalValidatedBpmnXml,
        context: OperationContext,
    ): AlignedBpmnXml {
        val summary = summarizer.summarize(bpmn.definition)
        val promptRunner =
            config.alignmentValidator
                .promptRunner(context)
                .withPromptContributor(request)

        val modelReport =
            promptRunner.createObject(
                promptFactory.prompt(request, contract, summary),
                BpmnAlignmentReport::class.java,
            ) ?: BpmnAlignmentReport(
                verdict = AlignmentVerdict.FAILED,
                rationale = "Alignment model failed to produce a structured report.",
                bpmnSummary = summary,
            )

        val report = postChecker.apply(modelReport.copy(bpmnSummary = summary))
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
