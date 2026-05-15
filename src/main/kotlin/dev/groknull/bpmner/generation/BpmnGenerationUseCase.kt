package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.core.GenerationMode
import dev.groknull.bpmner.generation.BpmnResult
import org.jmolecules.architecture.hexagonal.PrimaryPort

data class BpmnGenerationInput(
    val processDescription: String? = null,
    val processFile: String? = null,
    val outputFile: String = "output.bpmn",
    val styleGuide: String? = null,
    /**
     * Currently informational; only `SINGLE_SHOT` semantics are implemented and the readiness gate
     * runs for both values. `INTERACTIVE` behaviour lands with issue #66 (interactive clarification flow).
     */
    val mode: GenerationMode = GenerationMode.SINGLE_SHOT,
    val clarificationHistory: List<ClarificationExchange> = emptyList(),
)

@PrimaryPort
interface BpmnGenerationUseCase {
    fun generate(input: BpmnGenerationInput): BpmnResult
}
