package dev.groknull.bpmner.repair

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.core.ActionRetryPolicy
import dev.groknull.bpmner.core.BpmnRefinementFailureException
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.core.ValidatedBpmnXml
import dev.groknull.bpmner.repair.internal.BpmnRefinementEngine
import org.slf4j.LoggerFactory

@Agent(description = "Validate and repair generated BPMN until it is structurally valid")
internal class BpmnRepairAgent(
    private val refinementEngine: BpmnRefinementEngine,
) {
    private val logger = LoggerFactory.getLogger(BpmnRepairAgent::class.java)

    @Action(
        description = "Validate rendered BPMN, repair the typed definition if needed, and return validated BPMN XML",
        actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE,
    )
    fun validateAndRefineBpmn(
        request: BpmnRequest,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        context: ActionContext,
    ): ValidatedBpmnXml = try {
        refinementEngine.refine(request, graph, rendered, context)
    } catch (e: BpmnRefinementFailureException) {
        logger.warn(e.summary)
        throw e
    }
}
