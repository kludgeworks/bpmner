/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.outbound

import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.repair.BpmnLocalFixFailure
import dev.groknull.bpmner.repair.BpmnLocalRepairOutcome
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BpmnRepairPromptFactoryTest {
    @Test
    fun `patchFeedback annotates failed-local diagnostics and includes LLM diagnostics`() {
        val factory = factory()
        val definition = sampleDefinition()
        val llmDiag = lintDiagnostic("bpmner/name-02", "Task_1", "Use action verb", RepairKind.LLM_MODEL_PATCH)
        val localFailedDiag = lintDiagnostic("bpmner/name-01", "Task_2", "Strip type word", RepairKind.LOCAL_XML_FIX)
        val outcome =
            BpmnLocalRepairOutcome(
                listOf(BpmnLocalFixFailure(rule = "bpmner/name-01", elementId = "Task_2", reason = "handler boom")),
            )

        val prompt = factory.patchFeedback(definition, listOf(llmDiag, localFailedDiag), outcome)

        assertTrue(prompt.contains("rule=bpmner/name-02"), "expected LLM diagnostic in prompt")
        assertTrue(prompt.contains("rule=bpmner/name-01"), "expected failed-local diagnostic in prompt")
        assertTrue(
            prompt.contains("[local-fix-failed: handler boom]"),
            "expected failed-local annotation, got: $prompt",
        )
    }

    @Test
    fun `patchFeedback without local outcome renders diagnostics without local-fix marker`() {
        val factory = factory()
        val diag = lintDiagnostic("bpmner/name-02", "Task_1", "Use action verb", RepairKind.LLM_MODEL_PATCH)

        val prompt = factory.patchFeedback(sampleDefinition(), listOf(diag), BpmnLocalRepairOutcome.EMPTY)

        assertTrue(prompt.contains("rule=bpmner/name-02"))
        assertFalse(prompt.contains("local-fix-failed"))
    }

    @Test
    fun `fullRepairFeedback annotates failed-local diagnostics`() {
        val factory = factory()
        val definition = sampleDefinition()
        val attempt = attempt(definition, diagnostics = emptyList())
        val llmDiag = lintDiagnostic("bpmner/name-02", "Task_1", "Use action verb", RepairKind.LLM_MODEL_PATCH)
        val localFailedDiag = lintDiagnostic("bpmner/name-01", "Task_2", "Strip type word", RepairKind.LOCAL_XML_FIX)
        val outcome =
            BpmnLocalRepairOutcome(
                listOf(BpmnLocalFixFailure(rule = "bpmner/name-01", elementId = "Task_2", reason = "xsd invalid")),
            )

        val prompt = factory.fullRepairFeedback(attempt, listOf(llmDiag, localFailedDiag), outcome)

        assertTrue(prompt.contains("rule=bpmner/name-02"))
        assertTrue(prompt.contains("[local-fix-failed: xsd invalid]"))
    }

    private fun factory(): BpmnRepairPromptFactory {
        val fingerprints = BpmnFingerprintService()
        return BpmnRepairPromptFactory(NoopLintingPort, fingerprints, NoopRuleGuidancePort)
    }

    private fun lintDiagnostic(
        rule: String,
        elementId: String?,
        message: String,
        kind: RepairKind,
    ) = BpmnDiagnostic(
        source = BpmnDiagnosticSource.LINT,
        message = message,
        rule = rule,
        severity = BpmnDiagnosticSeverity.ERROR,
        elementId = elementId,
        kind = kind,
        repairScope = BpmnRepairScope.PHASE,
    )

    private fun sampleDefinition() =
        BpmnDefinition(
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
        override fun lint(bpmnXml: String): List<LintIssue> = emptyList()

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
