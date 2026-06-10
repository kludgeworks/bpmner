/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment.internal.adapter.inbound

import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.core.BpmnRequest
import org.springframework.stereotype.Component

@Component
internal class BpmnAlignmentPromptFactory(
    private val contractRenderer: ProcessContractMarkdownRenderer,
) {
    fun templateModel(
        request: BpmnRequest,
        contract: ProcessContract,
        summary: BpmnDefinitionSummary,
    ): Map<String, Any> = mapOf(
        "contractMarkdown" to contractRenderer.render(contract).trim(),
        "processId" to summary.processId,
        "processName" to summary.processName,
        "elementLines" to summary.elements.map { element ->
            "[${element.id}] ${element.type}: ${element.name ?: "(unnamed)"}"
        },
        "flowLines" to summary.flows.map { flow ->
            val condition = flow.conditionExpression?.let { " [if $it]" } ?: ""
            val name = flow.name?.let { " ($it)" } ?: ""
            "[${flow.id}] ${flow.sourceRef} → ${flow.targetRef}$condition$name"
        },
        "unreachableElementIds" to summary.unreachableElementIds,
        "processDescription" to request.processDescription,
    )
}
