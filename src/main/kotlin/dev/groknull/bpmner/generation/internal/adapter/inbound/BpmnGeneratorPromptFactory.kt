/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnNamingShapeAdvice
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import org.springframework.stereotype.Component

@Component
internal class BpmnGeneratorPromptFactory(
    private val contractRenderer: ProcessContractMarkdownRenderer,
) {
    fun templateModel(
        request: BpmnRequest,
        validatedContract: ValidatedProcessContract,
    ): Map<String, Any> = mapOf(
        "contractMarkdown" to contractRenderer.render(validatedContract.contract).trim(),
        "processDescription" to request.processDescription,
        "styleGuide" to (request.styleGuide ?: ""),
        "namingShapeAdvice" to BpmnNamingShapeAdvice.allAdvice().map { advice ->
            val examples = advice.examples.joinToString(", ") { "\"$it\"" }
            val avoid = advice.antiExamples.joinToString(", ") { "\"$it\"" }
            "- ${advice.kind}: ${advice.shape}\n    examples: $examples\n    avoid:    $avoid"
        },
    )

    fun generationExamples(): List<Pair<String, FlatBpmnDefinition>> = GenerationExamples.all
}
