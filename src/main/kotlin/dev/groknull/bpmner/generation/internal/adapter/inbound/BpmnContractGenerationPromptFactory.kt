/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnRequest

internal class BpmnContractGenerationPromptFactory(
    private val renderer: ProcessContractMarkdownRenderer = ProcessContractMarkdownRenderer(),
) {
    fun prompt(
        request: BpmnRequest,
        validatedContract: ValidatedProcessContract,
    ): String =
        buildString {
            appendLine("Generate a BPMN definition object from the validated process contract.")
            appendLine()
            appendLine("The validated ProcessContract is the primary and authoritative generation input.")
            appendLine("Use the original input only as secondary traceability context.")
            appendLine()
            appendLine("Contract-driven generation rules:")
            appendLine(
                "- Include the contract trigger, ordered activities, decisions, branches," +
                    " exception or rework paths, and end states.",
            )
            appendLine("- Represent actors only where current BPMN DTOs allow, usually in task names.")
            appendLine("- Do not add unsupported tasks, decisions, branches, actors, or end states.")
            appendLine(
                "- You may infer layout coordinates, waypoints, sequence flows," +
                    " and routing-only converging gateways needed for valid BPMN.",
            )
            appendLine("- Leave routing-only converging gateways unnamed.")
            appendLine()
            appendLine("Primary validated ProcessContract:")
            appendLine(renderer.render(validatedContract.contract).trim())
            appendLine()
            appendLine("Original input for traceability only:")
            appendLine(request.processDescription)
            if (!request.styleGuide.isNullOrBlank()) {
                appendLine()
                appendLine("Style guide:")
                appendLine(request.styleGuide)
            }
        }
}
