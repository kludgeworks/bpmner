package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.test.unit.FakeOperationContext
import dev.groknull.bpmner.core.BpmnAutoFixResult
import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.BpmnEvaluation
import dev.groknull.bpmner.core.BpmnFingerprintService
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.BpmnLintRuleCapability
import dev.groknull.bpmner.core.BpmnLocalFixFailure
import dev.groknull.bpmner.core.BpmnLocalRepairOutcome
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnRepairAttempt
import dev.groknull.bpmner.core.BpmnRepairScope
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.GlobalDiagnostics
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.LintIssue
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.OutlineMetrics
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.ProcessOutline
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.core.RepairKind
import dev.groknull.bpmner.core.ValidatedOutline
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnPatchApplier
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnRepairPromptFactory
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnRuleGuidancePort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BpmnRepairStrategiesTest {
    @Test
    fun `LlmPatchRepairStrategy is NotApplicable when only LOCAL_XML diagnostics exist with no failed-local matches`() {
        val ctx =
            contextOf(
                diagnostics = listOf(diag(rule = "klm/name-01", elementId = "Task_1", kind = RepairKind.LOCAL_XML_FIX)),
            )

        val result = strategy().repair(ctx)

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
                        diag(rule = "klm/name-02", elementId = "Task_1", kind = RepairKind.LLM_MODEL_PATCH),
                        diag(rule = "klm/name-01", elementId = "Task_2", kind = RepairKind.LOCAL_XML_FIX),
                    ),
                operationContext = operationContext,
            )

        strategy().repair(ctx)

        val prompt =
            operationContext.llmInvocations
                .single()
                .messages
                .joinToString("\n") { it.content }
        assertTrue(prompt.contains("rule=klm/name-02"), "expected LLM-routed diagnostic in prompt")
        assertFalse(prompt.contains("rule=klm/name-01"), "LOCAL_XML without local-failure should be filtered out")
    }

    @Test
    fun `LlmPatchRepairStrategy includes failed-local diagnostics with annotation`() {
        val operationContext = FakeOperationContext()
        operationContext.expectResponse(BpmnRepairPatch(operations = emptyList()))
        val localFailedDiag = diag(rule = "klm/name-01", elementId = "Task_2", kind = RepairKind.LOCAL_XML_FIX)
        val ctx =
            contextOf(
                diagnostics =
                    listOf(
                        diag(rule = "klm/name-02", elementId = "Task_1", kind = RepairKind.LLM_MODEL_PATCH),
                        localFailedDiag,
                    ),
                outcome =
                    BpmnLocalRepairOutcome(
                        listOf(BpmnLocalFixFailure(rule = "klm/name-01", elementId = "Task_2", reason = "boom")),
                    ),
                operationContext = operationContext,
            )

        strategy().repair(ctx)

        val prompt =
            operationContext.llmInvocations
                .single()
                .messages
                .joinToString("\n") { it.content }
        assertTrue(prompt.contains("rule=klm/name-02"))
        assertTrue(prompt.contains("rule=klm/name-01"))
        assertTrue(prompt.contains("[local-fix-failed: boom]"), "expected failure annotation, got: $prompt")
    }

    @Test
    fun `FullLlmRewriteRepairStrategy is NotApplicable when only LOCAL_XML diagnostics exist`() {
        val operationContext = FakeOperationContext()
        val ctx =
            contextOf(
                diagnostics = listOf(diag(rule = "klm/name-01", elementId = "Task_1", kind = RepairKind.LOCAL_XML_FIX)),
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
                diagnostics = listOf(diag(rule = "klm/name-02", elementId = "Task_1", kind = RepairKind.LLM_MODEL_PATCH)),
                operationContext = operationContext,
            )

        fullRewriteStrategy().repair(ctx)

        val prompt =
            operationContext.llmInvocations
                .single()
                .messages
                .joinToString("\n") { it.content }
        assertTrue(prompt.contains("rule=klm/name-02"))
    }

    private fun strategy(): LlmPatchRepairStrategy {
        val fingerprints = BpmnFingerprintService()
        val factory = BpmnRepairPromptFactory(NoopLintingPort, fingerprints, NoopRuleGuidancePort)
        return LlmPatchRepairStrategy(factory, BpmnPatchApplier())
    }

    private fun fullRewriteStrategy(): FullLlmRewriteRepairStrategy {
        val fingerprints = BpmnFingerprintService()
        val factory = BpmnRepairPromptFactory(NoopLintingPort, fingerprints, NoopRuleGuidancePort)
        return FullLlmRewriteRepairStrategy(factory)
    }

    private fun diag(
        rule: String,
        elementId: String,
        kind: RepairKind,
    ) = BpmnDiagnostic(
        source = BpmnDiagnosticSource.LINT,
        message = "violation of $rule",
        rule = rule,
        category = "error",
        elementId = elementId,
        kind = kind,
        repairScope = BpmnRepairScope.PHASE,
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
            promptRunner = operationContext.promptRunner(),
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
                outline = ValidatedOutline(ProcessOutline(BpmnRequest("test"), definition, OutlineMetrics(1, 0, 0, 0))),
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
