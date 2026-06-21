/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.outbound

import dev.groknull.bpmner.bpmn.RepairKind
import dev.groknull.bpmner.bpmn.internal.model.BpmnDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnEdge
import dev.groknull.bpmner.bpmn.internal.model.BpmnEndEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.internal.model.BpmnStartEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnUnrecognizedNode
import dev.groknull.bpmner.validation.BpmnDiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BpmnRepairPromptFactoryTest {
    @Test
    fun `patchFeedback renders LLM diagnostics in the prompt`() {
        // Phase 4 (#219): the prior local-fix-failure suffix was removed when
        // BpmnLocalRepairOutcome was deleted — GOAP's cost ordering + fingerprint cycle
        // delivers the escalation that the prior `failedLocally` flag tracked manually.
        val factory = RepairFixtures.factory()
        val definition = RepairFixtures.sampleDefinition()
        val llmDiag = RepairFixtures.lintDiagnostic(
            "bpmner/name-02",
            "Task_1",
            "Use action verb",
            RepairKind.LLM_MODEL_PATCH,
        )

        val prompt = factory.patchFeedback(definition, listOf(llmDiag))

        assertTrue(prompt.contains("rule=bpmner/name-02"), "expected LLM diagnostic in prompt")
        assertFalse(
            prompt.contains("local-fix-failed"),
            "post-Phase-4 prompt must not include the legacy local-fix-failed marker",
        )
    }

    @Test
    fun `patchFeedback groups diagnostics by severity with the ERROR or WARNING guidance preamble`() {
        val factory = RepairFixtures.factory()
        val errorDiag = RepairFixtures.lintDiagnostic(
            rule = "bpmner/name-02",
            elementId = "Task_1",
            message = "Use action verb",
            kind = RepairKind.LLM_MODEL_PATCH,
            severity = BpmnDiagnosticSeverity.ERROR,
        )
        val warningDiag = RepairFixtures.lintDiagnostic(
            rule = "bpmner/name-03",
            elementId = "Task_2",
            message = "Prefer sentence case",
            kind = RepairKind.LLM_MODEL_PATCH,
            severity = BpmnDiagnosticSeverity.WARNING,
        )

        val prompt = factory.patchFeedback(RepairFixtures.sampleDefinition(), listOf(errorDiag, warningDiag))

        assertTrue(prompt.contains("ERRORs — MUST be fixed"), "ERROR guidance preamble missing")
        assertTrue(prompt.contains("WARNINGs / INFO — advisory only"), "WARNING guidance preamble missing")
        // ERROR row precedes the WARNING row.
        val errorIdx = prompt.indexOf("rule=bpmner/name-02")
        val warningIdx = prompt.indexOf("rule=bpmner/name-03")
        assertTrue(errorIdx in 0 until warningIdx, "expected ERROR diagnostic before WARNING")
    }

    @Test
    fun `fullRepairFeedback embeds the rendered XML for LLM context`() {
        val factory = RepairFixtures.factory()
        val definition = RepairFixtures.sampleDefinition()
        val attempt = RepairFixtures.attempt(definition, diagnostics = emptyList())
        val llmDiag = RepairFixtures.lintDiagnostic(
            "bpmner/name-02",
            "Task_1",
            "Use action verb",
            RepairKind.LLM_MODEL_PATCH,
        )

        val prompt = factory.fullRepairFeedback(attempt, listOf(llmDiag))

        assertTrue(prompt.contains("Rendered BPMN XML:"), "expected rendered-XML section in full-repair prompt")
        assertTrue(prompt.contains("rule=bpmner/name-02"))
        assertFalse(prompt.contains("local-fix-failed"))
    }

    @Test
    fun `initialMessages refuses definition carrying BpmnUnrecognizedNode`() {
        val factory = RepairFixtures.factory()
        val definition = definitionWithUnrecognizedNode()
        val request = BpmnRequest(processDescription = "demo")
        val error = assertFailsWith<IllegalStateException> { factory.initialMessages(request, definition) }
        assertTrue(
            error.message.orEmpty().contains("pre-flight"),
            "expected guard message to name the pre-flight contract; got: ${error.message}",
        )
    }

    @Test
    fun `patchFeedback refuses definition carrying BpmnUnrecognizedNode`() {
        val factory = RepairFixtures.factory()
        val definition = definitionWithUnrecognizedNode()
        assertFailsWith<IllegalStateException> { factory.patchFeedback(definition, emptyList()) }
    }

    @Test
    fun `fullRepairFeedback refuses attempt whose definition carries BpmnUnrecognizedEventDefinition`() {
        val factory = RepairFixtures.factory()
        val definition = definitionWithUnrecognizedEventDefinition()
        assertFailsWith<IllegalStateException> {
            factory.fullRepairFeedback(RepairFixtures.attempt(definition, emptyList()), emptyList())
        }
    }

    private fun definitionWithUnrecognizedNode() = BpmnDefinition(
        processId = "Process_1",
        processName = "Sample",
        nodes = listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnUnrecognizedNode(id = "ch1", bpmnType = "bpmn:Choreography"),
            BpmnEndEvent("End_1", "End"),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "Start_1", "ch1"),
            BpmnEdge("Flow_2", "ch1", "End_1"),
        ),
    )

    private fun definitionWithUnrecognizedEventDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Sample",
        nodes = listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnIntermediateCatchEvent(
                id = "ic1",
                name = "Await compensate",
                eventDefinition = BpmnUnrecognizedEventDefinition(typeName = "bpmn:CompensateEventDefinition"),
            ),
            BpmnEndEvent("End_1", "End"),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "Start_1", "ic1"),
            BpmnEdge("Flow_2", "ic1", "End_1"),
        ),
    )
}
