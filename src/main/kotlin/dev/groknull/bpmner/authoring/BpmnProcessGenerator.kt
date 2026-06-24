/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import com.embabel.agent.api.common.OperationContext
import dev.groknull.bpmner.authoring.internal.domain.ProcessOutline
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import org.jmolecules.architecture.hexagonal.PrimaryPort
import org.jmolecules.architecture.hexagonal.SecondaryPort

@PrimaryPort
interface BpmnProcessGenerator {
    fun createOutline(ready: ReadyBpmnContext, contract: ValidatedProcessContract, context: OperationContext): ValidatedOutline

    fun composeGraph(outline: ValidatedOutline): LaidOutProcessGraph

    fun render(ready: ReadyBpmnContext, graph: LaidOutProcessGraph): RenderedBpmn
}

data class ValidatedOutline(
    val outline: ProcessOutline,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
    val fidelityReport: BpmnFidelityReport = BpmnFidelityReport.VALID,
) {
    val definition: BpmnDefinition
        get() = outline.definition
}

@SecondaryPort
interface BpmnRenderer {
    fun render(definition: BpmnDefinition): RenderedBpmn

    fun render(graph: LaidOutProcessGraph): RenderedBpmn
}

@SecondaryPort
interface BpmnAgentInvoker {
    fun generate(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): BpmnResult

    fun startAsync(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): String

    fun startAsync(
        request: BpmnRequest,
    ): String
}
