package dev.groknull.bpmner.alignment.internal.adapter.inbound

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import dev.groknull.bpmner.alignment.internal.domain.BpmnAlignmentPostChecker
import dev.groknull.bpmner.core.AlignedBpmnXml
import dev.groknull.bpmner.core.AlignmentVerdict
import dev.groknull.bpmner.core.BpmnAlignmentException
import dev.groknull.bpmner.core.BpmnAlignmentReport
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnSummarizer
import dev.groknull.bpmner.core.FinalValidatedBpmnXml
import dev.groknull.bpmner.core.ProcessContract
import org.jmolecules.architecture.hexagonal.PrimaryAdapter

@PrimaryAdapter
@Agent(description = "Verify semantic alignment between process contract and generated BPMN")
internal class BpmnAlignmentAgent(
    private val config: BpmnConfig,
    private val summarizer: BpmnSummarizer,
    private val postChecker: BpmnAlignmentPostChecker,
    private val promptFactory: BpmnAlignmentPromptFactory,
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
            )

        val report = postChecker.apply(modelReport.copy(bpmnSummary = summary))

        if (report.verdict == AlignmentVerdict.FAILED) {
            throw BpmnAlignmentException(
                message = "Generated BPMN does not align with process contract: ${report.rationale}",
                report = report,
            )
        }

        return AlignedBpmnXml(xml = bpmn.xml, alignmentReport = report)
    }
}
