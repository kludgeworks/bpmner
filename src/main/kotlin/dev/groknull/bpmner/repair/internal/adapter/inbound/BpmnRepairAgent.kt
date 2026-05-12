package dev.groknull.bpmner.repair.internal.adapter.inbound

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.core.ActionRetryPolicy
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.core.ValidatedBpmnXml
import dev.groknull.bpmner.repair.internal.domain.BpmnRefinementEngine
import org.jmolecules.architecture.hexagonal.PrimaryAdapter

@PrimaryAdapter
@Agent(description = "Refine and repair generated BPMN 2.0 diagrams to ensure technical and semantic validity")
internal class BpmnRepairAgent(
    private val refinementEngine: BpmnRefinementEngine,
) {
    @Action(
        description =
            "Iteratively validate and repair a generated BPMN process graph" +
                " until it passes all XSD and lint rules",
        actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE,
    )
    fun repair(
        request: BpmnRequest,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        context: ActionContext,
    ): ValidatedBpmnXml = refinementEngine.refine(request, graph, rendered, context)
}
