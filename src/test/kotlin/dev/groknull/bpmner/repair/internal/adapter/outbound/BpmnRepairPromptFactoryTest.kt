package dev.groknull.bpmner.repair.internal.adapter.outbound

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
import dev.groknull.bpmner.core.BpmnRepairRoute
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
import dev.groknull.bpmner.core.ValidatedOutline
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnRuleGuidancePort
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BpmnRepairPromptFactoryTest {
    @Test
    fun `patchFeedback annotates failed-local diagnostics and includes LLM diagnostics`() {
        val factory = factory()
        val definition = sampleDefinition()
        val llmDiag = lintDiagnostic("klm/name-02", "Task_1", "Use action verb", BpmnRepairRoute.LLM)
        val localFailedDiag = lintDiagnostic("klm/name-01", "Task_2", "Strip type word", BpmnRepairRoute.LOCAL_XML)
        val outcome =
            BpmnLocalRepairOutcome(
                listOf(BpmnLocalFixFailure(rule = "klm/name-01", elementId = "Task_2", reason = "handler boom")),
            )

        val prompt = factory.patchFeedback(definition, listOf(llmDiag, localFailedDiag), outcome)

        assertTrue(prompt.contains("rule=klm/name-02"), "expected LLM diagnostic in prompt")
        assertTrue(prompt.contains("rule=klm/name-01"), "expected failed-local diagnostic in prompt")
        assertTrue(
            prompt.contains("[local-fix-failed: handler boom]"),
            "expected failed-local annotation, got: $prompt",
        )
    }

    @Test
    fun `patchFeedback without local outcome renders diagnostics without local-fix marker`() {
        val factory = factory()
        val diag = lintDiagnostic("klm/name-02", "Task_1", "Use action verb", BpmnRepairRoute.LLM)

        val prompt = factory.patchFeedback(sampleDefinition(), listOf(diag), BpmnLocalRepairOutcome.EMPTY)

        assertTrue(prompt.contains("rule=klm/name-02"))
        assertFalse(prompt.contains("local-fix-failed"))
    }

    @Test
    fun `fullRepairFeedback annotates failed-local diagnostics`() {
        val factory = factory()
        val definition = sampleDefinition()
        val attempt = attempt(definition, diagnostics = emptyList())
        val llmDiag = lintDiagnostic("klm/name-02", "Task_1", "Use action verb", BpmnRepairRoute.LLM)
        val localFailedDiag = lintDiagnostic("klm/name-01", "Task_2", "Strip type word", BpmnRepairRoute.LOCAL_XML)
        val outcome =
            BpmnLocalRepairOutcome(
                listOf(BpmnLocalFixFailure(rule = "klm/name-01", elementId = "Task_2", reason = "xsd invalid")),
            )

        val prompt = factory.fullRepairFeedback(attempt, listOf(llmDiag, localFailedDiag), outcome)

        assertTrue(prompt.contains("rule=klm/name-02"))
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
        route: BpmnRepairRoute,
    ) = BpmnDiagnostic(
        source = BpmnDiagnosticSource.LINT,
        message = message,
        rule = rule,
        category = "error",
        elementId = elementId,
        repairRoute = route,
        repairScope = BpmnRepairScope.PHASE,
    )

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
                        shapeIdsByNodeId = definition.nodes.associate { it.id to "${it.id}_di" },
                        edgeDiagramIdsByEdgeId = definition.sequences.associate { it.id to "${it.id}_di" },
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
