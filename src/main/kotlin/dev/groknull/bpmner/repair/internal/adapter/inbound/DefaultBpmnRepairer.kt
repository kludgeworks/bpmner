/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.inbound

import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.repair.BpmnRepairer
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairAdvancer
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
    private val eventPublisher: ApplicationEventPublisher,
) : BpmnRepairer {
    private val logger = LoggerFactory.getLogger(DefaultBpmnRepairer::class.java)

    override fun validateInitial(
        ready: ReadyBpmnContext,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        contract: ValidatedProcessContract,
    ): ValidatedBpmnXml {
        val repairEval = advancer.initialEvaluation(ready, graph, rendered, contract)

        logAdvisoryDiagnostics(repairEval.evaluation.advisoryDiagnostics, repairEval.repairAttempts)
        val xml = repairEval.evaluation.validatedXml
            ?: repairEval.rendered?.xml
            ?: error("validateInitial fired with no validated XML and no rendered XML")

        val result = ValidatedBpmnXml(
            definition = repairEval.definition,
            xml = xml,
            diagnostics = repairEval.diagnostics,
            repairAttempts = repairEval.repairAttempts,
        )

        if (repairEval.diagnostics.isEmpty()) {
            eventPublisher.publishEvent(
                BpmnValidationPassedEvent(repairEval.request, result.xml, repairEval.repairAttempts),
            )
        } else {
            eventPublisher.publishEvent(
                BpmnValidationFailedEvent(
                    request = repairEval.request,
                    xml = repairEval.rendered?.xml ?: "",
                    diagnostics = repairEval.diagnostics,
                    attemptNumber = repairEval.history.size,
                    repairAttempts = repairEval.repairAttempts,
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
