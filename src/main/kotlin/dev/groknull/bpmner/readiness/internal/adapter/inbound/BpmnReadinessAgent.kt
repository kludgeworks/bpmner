/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.OperationContext
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.BpmnReadinessAssessedEvent
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.internal.domain.BpmnReadinessPostChecker
import org.jmolecules.architecture.hexagonal.Application
import org.springframework.context.ApplicationEventPublisher

@Application
@Agent(description = "Assess whether source text is ready for BPMN generation")
internal class BpmnReadinessAgent(
    private val config: BpmnConfig,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val promptFactory = BpmnReadinessPromptFactory(config.readiness)
    private val postChecker = BpmnReadinessPostChecker(config.readiness)

    @AchievesGoal(
        description = "Assess raw BPMN generation input for process readiness",
        export = Export(name = "assessReadiness", remote = true, startingInputTypes = [BpmnRequest::class]),
    )
    @Action(description = "Assess raw BPMN generation input for process readiness")
    fun assessReadiness(
        request: BpmnRequest,
        context: OperationContext,
    ): ProcessInputAssessment {
        val promptRunner = config.readinessAssessor.promptRunner(context).withPromptContributor(request)
        // Phase 5 (#220): `createObject` returns non-null per Embabel's contract; it throws on
        // failure. Let the typed exception propagate to the planner.
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
