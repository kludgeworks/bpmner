/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.core.ActionRetryPolicy
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.repair.internal.domain.BpmnRefinementEngine
import dev.groknull.bpmner.validation.ValidatedBpmnXml
import org.jmolecules.architecture.hexagonal.PrimaryAdapter

@PrimaryAdapter
@Agent(
    description =
    "Refine and repair generated BPMN 2.0 diagrams to ensure technical and semantic validity",
)
internal class BpmnRepairAgent(
    private val refinementEngine: BpmnRefinementEngine,
) {
    @AchievesGoal(
        description =
        "Refine and repair generated BPMN 2.0 diagrams to ensure technical and semantic validity",
        export =
        Export(
            name = "repairBpmn",
            remote = true,
            startingInputTypes = [
                BpmnRequest::class,
                LaidOutProcessGraph::class,
                RenderedBpmn::class,
                ValidatedProcessContract::class,
            ],
        ),
    )
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
        validatedContract: ValidatedProcessContract,
        context: ActionContext,
    ): ValidatedBpmnXml = // Take ValidatedProcessContract (the wrapper produced by BpmnContractAgent) rather
        // than the bare ProcessContract. The planner can satisfy this input via the existing
        // contract-extraction action; requiring a bare ProcessContract would leave the
        // planner stuck because no action in the agent graph produces one. Unwrap to the
        // bare ProcessContract before passing into the engine (engine signature unchanged).
        refinementEngine.refine(request, graph, rendered, validatedContract.contract, context)
}
