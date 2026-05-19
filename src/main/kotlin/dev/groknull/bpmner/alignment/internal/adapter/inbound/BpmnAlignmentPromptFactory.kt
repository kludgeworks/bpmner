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
    fun prompt(
        request: BpmnRequest,
        contract: ProcessContract,
        bpmnSummary: BpmnDefinitionSummary,
    ): String =
        buildString {
            appendLine(SYSTEM_INSTRUCTIONS)
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
            appendLine(TASK_INSTRUCTIONS)
            appendLine()
            appendLine("## Original BPMN request text")
            appendLine(request.processDescription)
        }

    companion object {
        private val SYSTEM_INSTRUCTIONS =
            """
            You are a BPMN alignment validator. Compare the generated BPMN diagram against the
            process contract and report only the deviations.

            Output shape (AlignmentFindings):
            - `issues`: a list of misalignments. Each entry is an AlignmentIssue with:
              - `elementId` — an existing id from the Process Contract or Generated BPMN Summary
                below. Do not invent new ids.
              - `classification` — one of:
                - ASSUMED: generated element is plausible workflow logic not in the contract.
                - UNSUPPORTED: generated element contradicts the contract or adds behavior not in source.
                - PARTIALLY_COVERED: contract item is partially present in the BPMN.
                - MISSING: contract item is absent from the BPMN.
            - `rationale`: 1-2 sentences summarising the outcome.

            Return an empty issues array when the BPMN fully aligns with the contract. That is the
            correct, expected output for an aligned diagram.
            """.trimIndent()

        private val TASK_INSTRUCTIONS =
            """
            ## Alignment Task
            List only misalignments. If every generated element is supported by the contract AND
            every contract item is covered by the BPMN, return an empty issues array.

            ### Worked Example — Aligned (empty issues)

            If a contract describes "Customer submits order, system validates payment, system ships
            order" and the BPMN renders exactly those three tasks with matching flow, return:
            ```
            {
              "issues": [],
              "rationale": "All three contract steps are present and correctly sequenced."
            }
            ```

            ### Worked Example — Misaligned

            If the same contract was generated as "Customer submits order, system ships order"
            (validation step missing) plus an unrelated "system sends marketing email" task,
            return:
            ```
            {
              "issues": [
                { "elementId": "act-validate-payment", "classification": "MISSING" },
                { "elementId": "act-send-marketing-email", "classification": "UNSUPPORTED" }
              ],
              "rationale": "Payment validation is missing from the BPMN; an unsourced marketing email task was added."
            }
            ```
            """.trimIndent()
    }
}
