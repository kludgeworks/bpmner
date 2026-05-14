package dev.groknull.bpmner.validation

import dev.groknull.bpmner.layout.LaidOutProcessGraph


import dev.groknull.bpmner.core.BpmnDefinition


import org.jmolecules.architecture.hexagonal.PrimaryPort

@PrimaryPort
interface BpmnValidator {
    fun evaluate(
        graph: LaidOutProcessGraph,
        definition: BpmnDefinition,
        rendered: RenderedBpmn?,
        renderFailureMessage: String? = null,
        repairAttempts: Int,
    ): BpmnEvaluation

    fun logDiagnosticSummary(diagnostics: List<BpmnDiagnostic>)
}
