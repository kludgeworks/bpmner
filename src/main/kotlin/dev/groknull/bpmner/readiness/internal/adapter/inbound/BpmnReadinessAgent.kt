package dev.groknull.bpmner.readiness.internal.adapter.inbound

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ProcessInputAssessment
import dev.groknull.bpmner.readiness.internal.domain.BpmnReadinessPostChecker
import org.jmolecules.architecture.hexagonal.PrimaryAdapter

@PrimaryAdapter
@Agent(description = "Assess whether source text is ready for BPMN generation")
internal class BpmnReadinessAgent(
    private val config: BpmnConfig,
    private val postChecker: BpmnReadinessPostChecker,
) {
    private val promptFactory = BpmnReadinessPromptFactory(config.readiness)

    @Action(description = "Assess raw BPMN generation input for process readiness")
    fun assessReadiness(
        request: BpmnRequest,
        context: OperationContext,
    ): ProcessInputAssessment {
        val promptRunner = config.readinessAssessor.promptRunner(context).withPromptContributor(request)
        val modelAssessment =
            promptRunner.createObject(
                promptFactory.prompt(request),
                ProcessInputAssessment::class.java,
            )
        return postChecker.apply(request, modelAssessment)
    }
}
