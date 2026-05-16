/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.alignment.internal.adapter.inbound

import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.internal.domain.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.core.BpmnRequest
import org.springframework.stereotype.Component

@Component
internal class BpmnAlignmentPromptFactory(
    private val contractRenderer: ProcessContractMarkdownRenderer,
) {
    fun prompt(
        request: BpmnRequest,
        contract: ProcessContract,
        bpmnSummary: BpmnDefinitionSummary,
    ): String =
        buildString {
            appendLine("Assess whether generated BPMN aligns semantically with process contract.")
            appendLine()
            appendLine("## Process Contract")
            appendLine(contractRenderer.render(contract))
            appendLine()
            appendLine("## Generated BPMN Summary")
            appendLine("Process ID: ${bpmnSummary.processId}")
            appendLine("Process Name: ${bpmnSummary.processName}")
            appendLine()
            appendLine("### Semantic Elements")
            bpmnSummary.elements.forEach { element ->
                appendLine("- [${element.id}] ${element.type}: ${element.name ?: "(unnamed)"}")
            }
            appendLine()
            appendLine("### Sequence Flows")
            bpmnSummary.flows.forEach { flow ->
                val condition = flow.conditionExpression?.let { " [if $it]" } ?: ""
                appendLine("- [${flow.id}] ${flow.sourceRef} → ${flow.targetRef}$condition${flow.name?.let { " ($it)" } ?: ""}")
            }

            if (bpmnSummary.unreachableElementIds.isNotEmpty()) {
                appendLine()
                appendLine("### Unreachable Elements")
                bpmnSummary.unreachableElementIds.forEach { id ->
                    appendLine("- $id")
                }
            }

            appendLine()
            appendLine("## Alignment Instructions")
            appendLine("- Compare every generated semantic element against the contract.")
            appendLine("- Classify each generated element as:")
            appendLine("  - SUPPORTED: explicitly mentioned or clearly implied by the contract.")
            appendLine("  - ASSUMED: plausible workflow logic necessary for flow.")
            appendLine("  - UNSUPPORTED: contradicts contract or adds behavior not in source.")
            appendLine("- Compare every item in the process contract against the generated BPMN.")
            appendLine("- Classify each contract item as:")
            appendLine("  - COVERED: present in the generated BPMN.")
            appendLine("  - PARTIALLY_COVERED: some aspects present but core behavior missing.")
            appendLine("  - MISSING: completely absent from the generated BPMN.")
            appendLine("- Return a BpmnAlignmentReport object.")
            appendLine("- Tie every classification to evidenceIds from the contract when possible.")
            appendLine()
            appendLine("Original BPMN request text:")
            appendLine(request.processDescription)
        }
}
