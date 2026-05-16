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

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.api.common.Actor
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.common.ai.model.ByRoleModelSelectionCriteria
import com.embabel.common.ai.model.LlmOptions
import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.repair.BpmnLocalFixFailure
import dev.groknull.bpmner.repair.BpmnLocalRepairOutcome
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnPatchApplier
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnRepairPromptFactory
import dev.groknull.bpmner.validation.BpmnAutoFixResult
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnEvaluation
import dev.groknull.bpmner.validation.BpmnFingerprintService
import dev.groknull.bpmner.validation.BpmnLintPhase
import dev.groknull.bpmner.validation.BpmnLintRuleCapability
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnRepairScope
import dev.groknull.bpmner.validation.BpmnRuleGuidancePort
import dev.groknull.bpmner.validation.GlobalDiagnostics
import dev.groknull.bpmner.validation.LintIssue
import dev.groknull.bpmner.validation.RepairKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        category = "error",
        elementId = elementId,
        kind = kind,
        repairScope = scope,
    )

    private fun contextOf(
        diagnostics: List<BpmnDiagnostic>,
        outcome: BpmnLocalRepairOutcome = BpmnLocalRepairOutcome.EMPTY,
        operationContext: FakeOperationContext = FakeOperationContext(),
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
                messages = emptyList(),
            )
        return BpmnRepairStrategyContext(
            attempt = attempt,
            request = BpmnRequest("do thing"),
            operationContext = operationContext,
            localOutcome = outcome,
        )
    }

    private fun renderedFrom(definition: BpmnDefinition): RenderedBpmn =
        RenderedBpmn(
            definition = definition,
            xml = "<bpmn/>",
            elementIndex =
                BpmnElementIndex(
                    processId = definition.processId,
                    nodeObjectRefs = definition.nodes.associate { it.id to "nodes[id=${it.id}]" },
                    edgeObjectRefs = definition.sequences.associate { it.id to "sequences[id=${it.id}]" },
                    shapeIdsByNodeId = definition.nodes.associate { it.id to "${it.id}_di" },
                    edgeDiagramIdsByEdgeId = definition.sequences.associate { it.id to "${it.id}_di" },
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

    private fun sampleDefinition() =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Sample",
            nodes =
                listOf(
                    BpmnNode("Start_1", "Start", NodeType.START_EVENT, BpmnBounds(80.0, 100.0, 36.0, 36.0)),
                    BpmnNode("Task_1", "Do thing", NodeType.USER_TASK, BpmnBounds(200.0, 80.0, 100.0, 80.0)),
                    BpmnNode("Task_2", "Do other", NodeType.USER_TASK, BpmnBounds(360.0, 80.0, 100.0, 80.0)),
                    BpmnNode("End_1", "End", NodeType.END_EVENT, BpmnBounds(520.0, 100.0, 36.0, 36.0)),
                ),
            sequences =
                listOf(
                    BpmnEdge("Flow_1", "Start_1", "Task_1", waypoints = listOf(BpmnWaypoint(116.0, 118.0))),
                    BpmnEdge("Flow_2", "Task_1", "Task_2", waypoints = listOf(BpmnWaypoint(300.0, 120.0))),
                    BpmnEdge("Flow_3", "Task_2", "End_1", waypoints = listOf(BpmnWaypoint(460.0, 120.0))),
                ),
        )

    private object NoopLintingPort : BpmnLintingPort {
        override fun lint(
            bpmnXml: String,
            phase: BpmnLintPhase,
        ): List<LintIssue> = emptyList()

        override fun autoFix(
            bpmnXml: String,
            issues: List<LintIssue>,
            phase: BpmnLintPhase,
        ): BpmnAutoFixResult? = null

        override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> = emptyMap()

        override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> = emptyMap()
    }

    private object NoopRuleGuidancePort : BpmnRuleGuidancePort {
        override fun getLlmRuleGuidance(): String = ""
    }
}
