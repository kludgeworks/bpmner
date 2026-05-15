package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.core.GenerationMode
import org.jmolecules.architecture.hexagonal.PrimaryPort

data class BpmnGenerationInput(
    val processDescription: String? = null,
    val processFile: String? = null,
    val outputFile: String? = null,
    val styleGuide: String? = null,
    val styleGuideContent: String? = null,
    val mode: GenerationMode = GenerationMode.SINGLE_SHOT,
    val clarificationHistory: List<ClarificationExchange> = emptyList(),
)

sealed interface StartGenerationOutcome {
    data class Started(
        val processId: String,
    ) : StartGenerationOutcome

    data class Blocked(
        val result: BpmnResult,
    ) : StartGenerationOutcome
}

@PrimaryPort
interface BpmnGenerationUseCase {
    fun generate(input: BpmnGenerationInput): BpmnResult

    fun startAsync(input: BpmnGenerationInput): StartGenerationOutcome
}
