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

package dev.groknull.bpmner.readiness.internal.adapter.inbound

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.BpmnReadinessAssessedEvent
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.internal.domain.BpmnReadinessPostChecker
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.context.ApplicationEventPublisher

@PrimaryAdapter
@Agent(description = "Assess whether source text is ready for BPMN generation")
internal class BpmnReadinessAgent(
    private val config: BpmnConfig,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val promptFactory = BpmnReadinessPromptFactory(config.readiness)
    private val postChecker = BpmnReadinessPostChecker(config.readiness)

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
        val assessment = postChecker.apply(request, modelAssessment)
        eventPublisher.publishEvent(BpmnReadinessAssessedEvent(request, assessment))
        return assessment
    }
}
