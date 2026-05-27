/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.common.ai.model.ByRoleModelSelectionCriteria
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.repair.BpmnLocalFixFailure
import dev.groknull.bpmner.repair.BpmnLocalRepairOutcome
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnPatchApplier
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnRepairPromptFactory
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Suppress("TooManyFunctions")
class BpmnRepairStrategiesTest {
    @Test
    fun `TargetedLabelRepairStrategy routes through repair-label actor`() {
        val operationContext = FakeOperationContext()
        operationContext.expectResponse(BpmnRepairPatch(operations = emptyList()))
        val ctx =
            contextOf(
                diagnostics =
                listOf(
                    diag(
                        rule = "label-rule",
                        elementId = "Task_1",
                        kind = RepairKind.LLM_MODEL_PATCH,
                        scope = BpmnRepairScope.LABEL,
                    ),
                ),
                operationContext = operationContext,
            )

        labelStrategy().repair(ctx)

        val invocation = operationContext.llmInvocations.single()
        val criteria = invocation.interaction.llm.criteria as ByRoleModelSelectionCriteria
        assertEquals("repair-label", criteria.role)
    }

    @Test
    fun `TargetedLabelRepairStrategy is NotApplicable when LLM returns no patch`() {
        val operationContext = FakeOperationContext()
        operationContext.expectResponse(null)
        val ctx =
            contextOf(
                diagnostics =
                listOf(
                    diag(
                        rule = "label-rule",
                        elementId = "Task_1",
                        kind = RepairKind.LLM_MODEL_PATCH,
                        scope = BpmnRepairScope.LABEL,
                    ),
                ),
                operationContext = operationContext,
            )

        val result = labelStrategy().repair(ctx)

        assertIs<BpmnRepairResult.NotApplicable>(result)
    }

    @Test
    fun `TargetedLabelRepairStrategy propagates provider failures`() {
        val operationContext = FakeOperationContext()
        val ctx =
            contextOf(
                diagnostics =
                listOf(
                    diag(
                        rule = "label-rule",
                        elementId = "Task_1",
                        kind = RepairKind.LLM_MODEL_PATCH,
                        scope = BpmnRepairScope.LABEL,
                    ),
                ),
                operationContext = operationContext,
            )

        assertFailsWith<IllegalStateException> {
            labelStrategy().repair(ctx)
        }
    }

    @Test
    fun `LlmPatchRepairStrategy routes through repair-patch actor`() {
        val operationContext = FakeOperationContext()
        operationContext.expectResponse(BpmnRepairPatch(operations = emptyList()))
        val ctx =
            contextOf(
                diagnostics =
                listOf(
                    diag(
                        rule = "patch-rule",
                        elementId = "Task_1",
                        kind = RepairKind.LLM_MODEL_PATCH,
                        scope = BpmnRepairScope.OUTLINE,
                    ),
                ),
                operationContext = operationContext,
            )

        patchStrategy().repair(ctx)

        val invocation = operationContext.llmInvocations.single()
        val criteria = invocation.interaction.llm.criteria as ByRoleModelSelectionCriteria
        assertEquals("repair-patch", criteria.role)
    }

    @Test
    fun `FullLlmRewriteRepairStrategy routes through repair-rewrite actor`() {
        val operationContext = FakeOperationContext()
        operationContext.expectResponse(sampleDefinition())
        val ctx =
            contextOf(
                diagnostics =
                listOf(
                    diag(
                        rule = "rewrite-rule",
                        elementId = "Task_1",
                        kind = RepairKind.LLM_XML_REWRITE,
                        scope = BpmnRepairScope.FULL_PROCESS,
                    ),
                ),
                operationContext = operationContext,
            )

        fullRewriteStrategy().repair(ctx)

        val invocation = operationContext.llmInvocations.single()
        val criteria = invocation.interaction.llm.criteria as ByRoleModelSelectionCriteria
        assertEquals("repair-rewrite", criteria.role)
    }

    @Test
    fun `LlmPatchRepairStrategy is NotApplicable when only LOCAL_XML diagnostics exist with no failed-local matches`() {
        val ctx =
            contextOf(
                diagnostics = listOf(diag(rule = "bpmner/name-01", elementId = "Task_1", kind = RepairKind.LOCAL_XML_FIX)),
            )

        val result = patchStrategy().repair(ctx)

        assertIs<BpmnRepairResult.NotApplicable>(result)
    }

    @Test
    fun `LlmPatchRepairStrategy includes LLM-routed diagnostics in prompt`() {
        val operationContext = FakeOperationContext()
        operationContext.expectResponse(BpmnRepairPatch(operations = emptyList()))
        val ctx =
            contextOf(
                diagnostics =
                listOf(
                    diag(rule = "bpmner/name-02", elementId = "Task_1", kind = RepairKind.LLM_MODEL_PATCH),
                    diag(rule = "bpmner/name-01", elementId = "Task_2", kind = RepairKind.LOCAL_XML_FIX),
                ),
                operationContext = operationContext,
            )

        patchStrategy().repair(ctx)

        val prompt =
            operationContext.llmInvocations
                .single()
                .messages
                .joinToString("\n") { it.content }
        assertTrue(prompt.contains("rule=bpmner/name-02"), "expected LLM-routed diagnostic in prompt")
        assertFalse(prompt.contains("rule=bpmner/name-01"), "LOCAL_XML without local-failure should be filtered out")
    }

    @Test
    fun `LlmPatchRepairStrategy includes failed-local diagnostics with annotation`() {
        val operationContext = FakeOperationContext()
        operationContext.expectResponse(BpmnRepairPatch(operations = emptyList()))
        val localFailedDiag = diag(rule = "bpmner/name-01", elementId = "Task_2", kind = RepairKind.LOCAL_XML_FIX)
        val ctx =
            contextOf(
                diagnostics =
                listOf(
                    diag(rule = "bpmner/name-02", elementId = "Task_1", kind = RepairKind.LLM_MODEL_PATCH),
                    localFailedDiag,
                ),
                outcome =
                BpmnLocalRepairOutcome(
                    listOf(BpmnLocalFixFailure(rule = "bpmner/name-01", elementId = "Task_2", reason = "boom")),
                ),
                operationContext = operationContext,
            )

        patchStrategy().repair(ctx)

        val prompt =
            operationContext.llmInvocations
                .single()
                .messages
                .joinToString("\n") { it.content }
        assertTrue(prompt.contains("rule=bpmner/name-02"))
        assertTrue(prompt.contains("rule=bpmner/name-01"))
        assertTrue(prompt.contains("[local-fix-failed: boom]"), "expected failure annotation, got: $prompt")
    }

    @Test
    fun `LlmPatchRepairStrategy is NotApplicable when LLM returns no patch`() {
        val operationContext = FakeOperationContext()
        operationContext.expectResponse(null)
        val ctx =
            contextOf(
                diagnostics = listOf(diag(rule = "bpmner/name-02", elementId = "Task_1", kind = RepairKind.LLM_MODEL_PATCH)),
                operationContext = operationContext,
            )

        val result = patchStrategy().repair(ctx)

        assertIs<BpmnRepairResult.NotApplicable>(result)
    }

    @Test
    fun `LlmPatchRepairStrategy propagates provider failures`() {
        val operationContext = FakeOperationContext()
        val ctx =
            contextOf(
                diagnostics = listOf(diag(rule = "bpmner/name-02", elementId = "Task_1", kind = RepairKind.LLM_MODEL_PATCH)),
                operationContext = operationContext,
            )

        assertFailsWith<IllegalStateException> {
            patchStrategy().repair(ctx)
        }
    }

    @Test
    fun `FullLlmRewriteRepairStrategy is NotApplicable when only LOCAL_XML diagnostics exist`() {
        val operationContext = FakeOperationContext()
        val ctx =
            contextOf(
                diagnostics = listOf(diag(rule = "bpmner/name-01", elementId = "Task_1", kind = RepairKind.LOCAL_XML_FIX)),
                operationContext = operationContext,
            )

        val result = fullRewriteStrategy().repair(ctx)

        assertIs<BpmnRepairResult.NotApplicable>(result)
        assertEquals(0, operationContext.llmInvocations.size)
    }

    @Test
    fun `FullLlmRewriteRepairStrategy fires for LLM-routed diagnostics and includes them in prompt`() {
        val operationContext = FakeOperationContext()
        operationContext.expectResponse(sampleDefinition())
        val ctx =
            contextOf(
                diagnostics = listOf(diag(rule = "bpmner/name-02", elementId = "Task_1", kind = RepairKind.LLM_MODEL_PATCH)),
                operationContext = operationContext,
            )

        fullRewriteStrategy().repair(ctx)

        val prompt =
            operationContext.llmInvocations
                .single()
                .messages
                .joinToString("\n") { it.content }
        assertTrue(prompt.contains("rule=bpmner/name-02"))
    }

    @Test
    fun `FullLlmRewriteRepairStrategy includes repair history in prompt`() {
        val operationContext = FakeOperationContext()
        operationContext.expectResponse(sampleDefinition())
        val historyMessage = com.embabel.chat.AssistantMessage("Previous attempt was close")
        val ctx =
            contextOf(
                diagnostics = listOf(diag(rule = "bpmner/name-02", elementId = "Task_1", kind = RepairKind.LLM_MODEL_PATCH)),
                operationContext = operationContext,
                messages = listOf(historyMessage),
            )

        fullRewriteStrategy().repair(ctx)

        val invocation = operationContext.llmInvocations.single()
        assertTrue(
            invocation.messages.contains(historyMessage),
            "expected repair history to be included in LLM invocation",
        )
    }

    @Test
    fun `FullLlmRewriteRepairStrategy is NotApplicable when LLM returns no definition`() {
        val operationContext = FakeOperationContext()
        operationContext.expectResponse(null)
        val ctx =
            contextOf(
                diagnostics = listOf(diag(rule = "bpmner/name-02", elementId = "Task_1", kind = RepairKind.LLM_MODEL_PATCH)),
                operationContext = operationContext,
            )

        val result = fullRewriteStrategy().repair(ctx)

        assertIs<BpmnRepairResult.NotApplicable>(result)
    }

    private fun labelStrategy(): TargetedLabelRepairStrategy {
        val fingerprints = BpmnFingerprintService()
        val factory = BpmnRepairPromptFactory(NoopLintingPort, fingerprints, NoopRuleGuidancePort)
        return TargetedLabelRepairStrategy(config(), factory, BpmnPatchApplier())
    }

    private fun patchStrategy(): LlmPatchRepairStrategy {
        val fingerprints = BpmnFingerprintService()
        val factory = BpmnRepairPromptFactory(NoopLintingPort, fingerprints, NoopRuleGuidancePort)
        return LlmPatchRepairStrategy(config(), factory, BpmnPatchApplier())
    }

    private fun fullRewriteStrategy(): FullLlmRewriteRepairStrategy {
        val fingerprints = BpmnFingerprintService()
        val factory = BpmnRepairPromptFactory(NoopLintingPort, fingerprints, NoopRuleGuidancePort)
        return FullLlmRewriteRepairStrategy(config(), factory)
    }

    private fun config() = BpmnConfig()

    private fun diag(
        rule: String,
        elementId: String,
        kind: RepairKind,
        scope: BpmnRepairScope = BpmnRepairScope.PHASE,
    ) = BpmnDiagnostic(
        source = BpmnDiagnosticSource.LINT,
        message = "violation of $rule",
        rule = rule,
        severity = BpmnDiagnosticSeverity.ERROR,
        elementId = elementId,
        kind = kind,
        repairScope = scope,
    )

    private fun contextOf(
        diagnostics: List<BpmnDiagnostic>,
        outcome: BpmnLocalRepairOutcome = BpmnLocalRepairOutcome.EMPTY,
        operationContext: FakeOperationContext = FakeOperationContext(),
        messages: List<com.embabel.chat.Message> = emptyList(),
    ): BpmnRepairStrategyContext {
        val definition = sampleDefinition()
        val rendered = renderedFrom(definition)
        val evaluation =
            BpmnEvaluation(
                definition = definition,
                rendered = rendered,
                diagnostics = diagnostics,
                globalDiagnostics = GlobalDiagnostics(diagnostics),
                validatedXml = null,
            )
        val attempt =
            BpmnRepairAttempt(
                attemptNumber = 1,
                repairAttempts = 0,
                graph = laidOutGraph(definition),
                evaluation = evaluation,
                messages = messages,
            )
        return BpmnRepairStrategyContext(
            attempt = attempt,
            request = BpmnRequest("do thing"),
            operationContext = operationContext,
            localOutcome = outcome,
        )
    }

    private fun renderedFrom(definition: BpmnDefinition): RenderedBpmn = RenderedBpmn(
        definition = definition,
        xml = "<bpmn/>",
        elementIndex =
        BpmnElementIndex(
            processId = definition.processId,
            nodeObjectRefs = definition.nodes.associate { it.id to "nodes[id=${it.id}]" },
            edgeObjectRefs = definition.sequences.associate { it.id to "sequences[id=${it.id}]" },
        ),
    )

    private fun laidOutGraph(definition: BpmnDefinition): LaidOutProcessGraph {
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
        return LaidOutProcessGraph(OwnedElementGraph(composed, elementOwners, objectOwners), definition)
    }

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
