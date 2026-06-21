/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.inbound

import com.embabel.agent.api.common.ActionContext
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.repair.BpmnRepairer
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairAdvancer
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairLoop
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnValidationFailedEvent
import dev.groknull.bpmner.validation.BpmnValidationPassedEvent
import dev.groknull.bpmner.validation.ValidatedBpmnXml
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@PrimaryAdapter
@Component
internal class DefaultBpmnRepairer(
    private val advancer: BpmnRepairAdvancer,
    private val repairLoop: BpmnRepairLoop,
    private val eventPublisher: ApplicationEventPublisher,
) : BpmnRepairer {
    private val logger = LoggerFactory.getLogger(DefaultBpmnRepairer::class.java)

    override fun validateInitial(
        ready: ReadyBpmnContext,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        contract: ValidatedProcessContract,
        context: ActionContext,
    ): ValidatedBpmnXml {
        val seedEval = advancer.initialEvaluation(ready, graph, rendered, contract)

        // Run the RepeatUntilAcceptable loop — no-op when seedEval.diagnosticsResolved.
        val finalEval = repairLoop.run(seedEval, context)

        logAdvisoryDiagnostics(finalEval.evaluation.advisoryDiagnostics, finalEval.repairAttempts)
        val xml = finalEval.evaluation.validatedXml
            ?: finalEval.rendered?.xml
            ?: error("validateInitial fired with no validated XML and no rendered XML")

        val result = ValidatedBpmnXml(
            definition = finalEval.definition,
            xml = xml,
            diagnostics = finalEval.diagnostics,
            repairAttempts = finalEval.repairAttempts,
        )

        if (finalEval.diagnostics.isEmpty()) {
            eventPublisher.publishEvent(
                BpmnValidationPassedEvent(finalEval.request, result.xml, finalEval.repairAttempts),
            )
        } else {
            eventPublisher.publishEvent(
                BpmnValidationFailedEvent(
                    request = finalEval.request,
                    xml = finalEval.rendered?.xml ?: "",
                    diagnostics = finalEval.diagnostics,
                    attemptNumber = finalEval.history.size,
                    repairAttempts = finalEval.repairAttempts,
                ),
            )
        }

        return result
    }

    private fun logAdvisoryDiagnostics(advisory: List<BpmnDiagnostic>, repairAttempts: Int) {
        if (advisory.isEmpty()) return
        logger.info(
            "Pipeline succeeded after {} repair attempt(s) with {} advisory diagnostic(s) remaining",
            repairAttempts,
            advisory.size,
        )
    }
}
