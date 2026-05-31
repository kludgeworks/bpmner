/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.prompts

import com.embabel.common.textio.template.JinjavaTemplateRenderer
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.alignment.BpmnSummaryElement
import dev.groknull.bpmner.alignment.BpmnSummaryFlow
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.core.BpmnRequest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Substring coverage for the alignment validator template. No corresponding factory test
 * existed for BpmnAlignmentPromptFactory — this test gives the alignment prompt the same
 * content-level guardrails the other prompts enjoy.
 */
class CheckAlignmentTemplateTest {
    private val renderer = JinjavaTemplateRenderer()
    private val contractRenderer = ProcessContractMarkdownRenderer()

    @Test
    fun `template includes system instructions and the misaligned worked example`() {
        val prompt = render(sampleSummary())

        // AlignmentFindings shape + classification enum descriptions live in the JSON-schema
        // annotations on AlignmentFindings / AlignmentIssue / AlignmentClassification. The template
        // carries only the role, the dynamic context, and one worked example.
        assertTrue(prompt.contains("You are a BPMN alignment validator"))
        assertTrue(prompt.contains("Worked Example — Misaligned"))
        assertTrue(prompt.contains("\"classification\": \"MISSING\""))
        assertTrue(prompt.contains("\"classification\": \"UNSUPPORTED\""))
    }

    @Test
    fun `template renders process contract markdown and bpmn summary`() {
        val prompt = render(sampleSummary())

        assertTrue(prompt.contains("## Process Contract"))
        assertTrue(prompt.contains("## Generated BPMN Summary"))
        assertTrue(prompt.contains("Process ID: Process_1"))
        assertTrue(prompt.contains("Process Name: Make Toast"))
        assertTrue(prompt.contains("### Semantic Elements"))
        assertTrue(prompt.contains("### Sequence Flows"))
    }

    @Test
    fun `template formats elements with id, type, and name fallback`() {
        val prompt = render(
            sampleSummary().copy(
                elements = listOf(
                    BpmnSummaryElement(id = "task1", type = "USER_TASK", name = "Toast bread"),
                    BpmnSummaryElement(id = "gw1", type = "EXCLUSIVE_GATEWAY", name = null),
                ),
            ),
        )

        assertTrue(prompt.contains("- [task1] USER_TASK: Toast bread"))
        assertTrue(prompt.contains("- [gw1] EXCLUSIVE_GATEWAY: (unnamed)"))
    }

    @Test
    fun `template formats flows with optional condition and name suffixes`() {
        val prompt = render(
            sampleSummary().copy(
                flows = listOf(
                    BpmnSummaryFlow(id = "f1", sourceRef = "start", targetRef = "task1"),
                    BpmnSummaryFlow(
                        id = "f2",
                        sourceRef = "gw1",
                        targetRef = "task2",
                        conditionExpression = "ready == true",
                        name = "go",
                    ),
                ),
            ),
        )

        assertTrue(prompt.contains("- [f1] start → task1"))
        assertTrue(prompt.contains("- [f2] gw1 → task2 [if ready == true] (go)"))
    }

    @Test
    fun `template includes unreachable elements section when present`() {
        val prompt = render(sampleSummary().copy(unreachableElementIds = listOf("orphan1", "orphan2")))

        assertTrue(prompt.contains("### Unreachable Elements"))
        assertTrue(prompt.contains("- orphan1"))
        assertTrue(prompt.contains("- orphan2"))
    }

    @Test
    fun `template omits unreachable section when none present`() {
        val prompt = render(sampleSummary())
        assertTrue(!prompt.contains("### Unreachable Elements"))
    }

    @Test
    fun `template echoes original request prose at the end`() {
        val prompt = render(sampleSummary(), request = BpmnRequest("Make toast for breakfast."))
        assertTrue(prompt.contains("## Original BPMN request text"))
        assertTrue(prompt.contains("Make toast for breakfast."))
    }

    private fun sampleSummary() = BpmnDefinitionSummary(
        processId = "Process_1",
        processName = "Make Toast",
        elements = listOf(
            BpmnSummaryElement(id = "start", type = "START_EVENT", name = "Start"),
            BpmnSummaryElement(id = "task1", type = "USER_TASK", name = "Toast bread"),
            BpmnSummaryElement(id = "end", type = "END_EVENT", name = "End"),
        ),
        flows = listOf(
            BpmnSummaryFlow(id = "f1", sourceRef = "start", targetRef = "task1"),
            BpmnSummaryFlow(id = "f2", sourceRef = "task1", targetRef = "end"),
        ),
    )

    private fun render(
        summary: BpmnDefinitionSummary,
        request: BpmnRequest = BpmnRequest("Make toast."),
    ): String = renderer.renderLoadedTemplate("bpmner/check_alignment", model(summary, request))

    private fun model(summary: BpmnDefinitionSummary, request: BpmnRequest): Map<String, Any> = mapOf(
        "contractMarkdown" to contractRenderer.render(sampleContract()).trim(),
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

    private fun sampleContract() = ProcessContract(
        id = "contract-1",
        processName = "Make Toast",
        summary = "Toast bread for breakfast.",
        trigger = "Hunger",
        activities = listOf(ContractActivity(id = "act-toast", name = "Toast bread")),
        endStates = listOf(ContractEndState(id = "end-served", name = "Toast served")),
    )
}
