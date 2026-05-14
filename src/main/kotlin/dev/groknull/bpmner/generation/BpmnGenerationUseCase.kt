package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.BpmnResult
import dev.groknull.bpmner.core.GenerationMode
import org.jmolecules.architecture.hexagonal.PrimaryPort

data class BpmnGenerationInput(
    val processDescription: String? = null,
    val processFile: String? = null,
    val outputFile: String = "output.bpmn",
    val styleGuide: String? = null,
    val mode: GenerationMode = GenerationMode.SINGLE_SHOT,
)

@PrimaryPort
interface BpmnGenerationUseCase {
    fun generate(input: BpmnGenerationInput): BpmnResult
}
