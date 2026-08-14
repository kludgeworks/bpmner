/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.prompts

import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.alignment.BpmnSummaryElement
import dev.groknull.bpmner.alignment.BpmnSummaryFlow
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.StandardLoopCharacteristics
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractStart
import dev.groknull.bpmner.contract.ContractTrigger
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.llm.PromptJsonRenderer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Substring coverage for the alignment validator template. No corresponding factory test
 * existed for BpmnAlignmentPromptFactory — this test gives the alignment prompt the same
 * content-level guardrails the other prompts enjoy.
 */
class CheckAlignmentTemplateTest {
    private val renderer = JinjavaTemplateRenderer()
    private val jsonRenderer = PromptJsonRenderer(jacksonObjectMapper())

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
    fun `template renders process contract and bpmn summary as JSON`() {
        val prompt = render(sampleSummary())

        assertTrue(prompt.contains("## Process Contract (JSON)"))
        assertTrue(prompt.contains("## Generated BPMN Summary (JSON)"))
        assertTrue(prompt.contains("\"processId\":\"Process_1\""))
        assertTrue(prompt.contains("\"processName\":\"Make Toast\""))
    }

    @Test
    fun `template carries element and flow data through the serialised summary`() {
        val prompt = render(
            sampleSummary().copy(
                elements = listOf(
                    BpmnSummaryElement(id = "task1", type = "USER_TASK", name = "Toast bread"),
                    BpmnSummaryElement(
                        id = "task2",
                        type = "SERVICE_TASK",
                        name = "Retry toast",
                        standardLoop = StandardLoopCharacteristics(testBefore = false, loopCondition = "burnt"),
                    ),
                ),
                flows = listOf(
                    BpmnSummaryFlow(
                        id = "f2",
                        sourceRef = "gw1",
                        targetRef = "task2",
                        conditionExpression = "ready == true",
                        name = "go",
                    ),
                ),
                unreachableElementIds = listOf("orphan1"),
            ),
        )

        assertTrue(prompt.contains("\"id\":\"task1\"") && prompt.contains("\"type\":\"USER_TASK\""))
        assertTrue(prompt.contains("\"testBefore\":false") && prompt.contains("\"loopCondition\":\"burnt\""))
        assertTrue(prompt.contains("\"sourceRef\":\"gw1\"") && prompt.contains("\"conditionExpression\":\"ready == true\""))
        assertTrue(prompt.contains("\"unreachableElementIds\":[\"orphan1\"]"))
    }

    @Test
    fun `template echoes original request prose at the end`() {
        val prompt = render(sampleSummary(), request = BpmnRequest("Make toast for breakfast."))
        assertTrue(prompt.contains("## Original BPMN request text"))
        assertTrue(prompt.contains("Make toast for breakfast."))
    }

    @Test
    fun `contractJson contains SERVICE and NORMAL kind discriminators for default activity and end-state kinds`() {
        val prompt = render(sampleSummary())

        assertTrue(
            prompt.contains("\"kind\":\"SERVICE\""),
            "Service activity must carry the SERVICE kind discriminator; got:\n$prompt",
        )
        assertTrue(
            prompt.contains("\"kind\":\"NORMAL\""),
            "Normal end state must carry the NORMAL kind discriminator; got:\n$prompt",
        )
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
        "contractJson" to jsonRenderer.render(sampleContract()),
        "bpmnSummaryJson" to jsonRenderer.render(summary),
        "processDescription" to request.processDescription,
    )

    private fun sampleContract() = ProcessContract(
        id = "contract-1",
        processName = "Make Toast",
        summary = "Toast bread for breakfast.",
        start = ContractStart(ContractTrigger.None("Hunger")),
        activities = listOf(ContractActivity(id = "act-toast", name = "Toast bread")),
        endStates = listOf(ContractEndState(id = "end-served", name = "Toast served")),
    )
}
