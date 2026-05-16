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

package dev.groknull.bpmner.repair.internal.adapter.inbound

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.core.ActionRetryPolicy
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.repair.internal.domain.BpmnRefinementEngine
import dev.groknull.bpmner.validation.ValidatedBpmnXml
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
