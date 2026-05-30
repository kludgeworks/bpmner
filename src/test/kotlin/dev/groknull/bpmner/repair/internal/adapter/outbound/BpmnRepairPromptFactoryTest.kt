/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions") // @Test methods + fixture builders for each scenario

package dev.groknull.bpmner.repair.internal.adapter.outbound

import com.embabel.common.textio.template.JinjavaTemplateRenderer
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.core.BpmnUnrecognizedNode
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.validation.BpmnAutoFixResult
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSeverity
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnEvaluation
import dev.groknull.bpmner.validation.BpmnFingerprintService
import dev.groknull.bpmner.validation.BpmnLintRuleCapability
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnRepairScope
import dev.groknull.bpmner.validation.BpmnRuleGuidancePort
import dev.groknull.bpmner.validation.GlobalDiagnostics
import dev.groknull.bpmner.validation.LintIssue
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
        val factory = factory()
        val definition = sampleDefinition()
        val llmDiag = lintDiagnostic("bpmner/name-02", "Task_1", "Use action verb", RepairKind.LLM_MODEL_PATCH)

        val prompt = factory.patchFeedback(definition, listOf(llmDiag))

        assertTrue(prompt.contains("rule=bpmner/name-02"), "expected LLM diagnostic in prompt")
        assertFalse(
            prompt.contains("local-fix-failed"),
            "post-Phase-4 prompt must not include the legacy local-fix-failed marker",
        )
    }

    @Test
    fun `patchFeedback groups diagnostics by severity with the ERROR or WARNING guidance preamble`() {
        val factory = factory()
        val errorDiag = lintDiagnostic(
            rule = "bpmner/name-02",
            elementId = "Task_1",
            message = "Use action verb",
            kind = RepairKind.LLM_MODEL_PATCH,
            severity = BpmnDiagnosticSeverity.ERROR,
        )
        val warningDiag = lintDiagnostic(
            rule = "bpmner/name-03",
            elementId = "Task_2",
            message = "Prefer sentence case",
            kind = RepairKind.LLM_MODEL_PATCH,
            severity = BpmnDiagnosticSeverity.WARNING,
        )

        val prompt = factory.patchFeedback(sampleDefinition(), listOf(errorDiag, warningDiag))

        assertTrue(prompt.contains("ERRORs — MUST be fixed"), "ERROR guidance preamble missing")
        assertTrue(prompt.contains("WARNINGs / INFO — advisory only"), "WARNING guidance preamble missing")
        // ERROR row precedes the WARNING row.
        val errorIdx = prompt.indexOf("rule=bpmner/name-02")
        val warningIdx = prompt.indexOf("rule=bpmner/name-03")
        assertTrue(errorIdx in 0 until warningIdx, "expected ERROR diagnostic before WARNING")
    }

    @Test
    fun `fullRepairFeedback embeds the rendered XML for LLM context`() {
        val factory = factory()
        val definition = sampleDefinition()
        val attempt = attempt(definition, diagnostics = emptyList())
        val llmDiag = lintDiagnostic("bpmner/name-02", "Task_1", "Use action verb", RepairKind.LLM_MODEL_PATCH)

        val prompt = factory.fullRepairFeedback(attempt, listOf(llmDiag))

        assertTrue(prompt.contains("Rendered BPMN XML:"), "expected rendered-XML section in full-repair prompt")
        assertTrue(prompt.contains("rule=bpmner/name-02"))
        assertFalse(prompt.contains("local-fix-failed"))
    }

    @Test
    fun `initialMessages refuses definition carrying BpmnUnrecognizedNode`() {
        val factory = factory()
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
        val factory = factory()
        val definition = definitionWithUnrecognizedNode()
        assertFailsWith<IllegalStateException> { factory.patchFeedback(definition, emptyList()) }
    }

    @Test
    fun `fullRepairFeedback refuses attempt whose definition carries BpmnUnrecognizedEventDefinition`() {
        val factory = factory()
        val definition = definitionWithUnrecognizedEventDefinition()
        assertFailsWith<IllegalStateException> {
            factory.fullRepairFeedback(attempt(definition, emptyList()), emptyList())
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

    private fun factory(): BpmnRepairPromptFactory {
        val fingerprints = BpmnFingerprintService()
        return BpmnRepairPromptFactory(
            NoopLintingPort,
            fingerprints,
            NoopRuleGuidancePort,
            JinjavaTemplateRenderer(),
        )
    }

    private fun lintDiagnostic(
        rule: String,
        elementId: String?,
        message: String,
        kind: RepairKind,
        severity: BpmnDiagnosticSeverity = BpmnDiagnosticSeverity.ERROR,
    ) = BpmnDiagnostic(
        source = BpmnDiagnosticSource.LINT,
        message = message,
        rule = rule,
        severity = severity,
        elementId = elementId,
        kind = kind,
        repairScope = BpmnRepairScope.PHASE,
    )

    private fun sampleDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Sample",
        nodes =
        listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnUserTask("Task_1", "Do thing"),
            BpmnUserTask("Task_2", "Do other"),
            BpmnEndEvent("End_1", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_1", "Start_1", "Task_1"),
            BpmnEdge("Flow_2", "Task_1", "Task_2"),
            BpmnEdge("Flow_3", "Task_2", "End_1"),
        ),
    )

    private fun attempt(
        definition: BpmnDefinition,
        diagnostics: List<BpmnDiagnostic>,
    ): BpmnRepairAttempt {
        val rendered =
            RenderedBpmn(
                definition = definition,
                xml = "<bpmn/>",
                elementIndex =
                BpmnElementIndex(
                    processId = definition.processId,
                    nodeObjectRefs = definition.nodes.associate { it.id to "nodes[id=${it.id}]" },
                    edgeObjectRefs = definition.sequences.associate { it.id to "sequences[id=${it.id}]" },
                ),
            )
        val evaluation =
            BpmnEvaluation(
                definition = definition,
                rendered = rendered,
                diagnostics = diagnostics,
                globalDiagnostics = GlobalDiagnostics(diagnostics),
                validatedXml = null,
            )
        val owner = "phase:main"
        val objectOwners =
            buildMap {
                put("process", owner)
                definition.nodes.forEach { put("nodes[id=${it.id}]", owner) }
                definition.sequences.forEach { put("sequences[id=${it.id}]", owner) }
            }
        val composed =
            ComposedProcessGraph(
                definition = definition,
                objectOwnersByObjectRef = objectOwners,
            )
        val elementOwners =
            buildMap {
                put(definition.processId, owner)
                definition.nodes.forEach {
                    put(it.id, owner)
                    put("${it.id}_di", owner)
                }
                definition.sequences.forEach {
                    put(it.id, owner)
                    put("${it.id}_di", owner)
                }
            }
        val graph = LaidOutProcessGraph(OwnedElementGraph(composed, elementOwners, objectOwners), definition)
        return BpmnRepairAttempt(
            attemptNumber = 1,
            repairAttempts = 0,
            graph = graph,
            evaluation = evaluation,
            messages = emptyList(),
        )
    }

    private object NoopLintingPort : BpmnLintingPort {
        override fun lint(definition: BpmnDefinition): List<LintIssue> = emptyList()

        override fun autoFix(
            bpmnXml: String,
            issues: List<LintIssue>,
        ): BpmnAutoFixResult? = null

        override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> = emptyMap()

        override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> = emptyMap()
    }

    private object NoopRuleGuidancePort : BpmnRuleGuidancePort {
        override fun getLlmRuleGuidance(): String = ""
    }
}
