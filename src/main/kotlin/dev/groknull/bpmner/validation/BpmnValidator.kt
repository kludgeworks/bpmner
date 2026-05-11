package dev.groknull.bpmner.validation

import dev.groknull.bpmner.core.BpmnAttemptRecord
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnEvaluation
import dev.groknull.bpmner.core.BpmnRepairAttempt
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
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

    fun toRecord(
        attempt: BpmnRepairAttempt,
        repairPromptFingerprint: String? = null,
    ): BpmnAttemptRecord

    fun logDiagnosticSummary(diagnostics: List<BpmnDiagnostic>)
}
