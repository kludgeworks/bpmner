package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.BpmnResult
import dev.groknull.bpmner.core.GenerationMode
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
)

@PrimaryPort
interface BpmnGenerationUseCase {
    fun generate(input: BpmnGenerationInput): BpmnResult
}
