package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.test.unit.FakeOperationContext
import dev.groknull.bpmner.core.BpmnAutoFixChange
import dev.groknull.bpmner.core.BpmnAutoFixError
import dev.groknull.bpmner.core.BpmnAutoFixResult
import dev.groknull.bpmner.core.BpmnAutoFixSkip
import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.BpmnEvaluation
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.BpmnLintRuleCapability
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnRepairAttempt
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
import dev.groknull.bpmner.core.XsdValidationIssue
import dev.groknull.bpmner.generation.BpmnXmlParser
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LintLocalRepairStrategyTest {
    @Test
    fun `returns NotApplicable when no LOCAL_XML_FIX diagnostics are stamped`() {
        val strategy = strategy(lint = FakeLintingPort())
        val context = contextOf(diagnostics = listOf(lintDiagnostic(kind = RepairKind.LLM_MODEL_PATCH)))

        assertIs<BpmnRepairResult.NotApplicable>(strategy.repair(context))
    }

    @Test
    fun `applied auto-fix returns Repaired with parsed definition`() {
        val parsed = otherValidDefinition()
        val lint =
            FakeLintingPort(
                autoFixResult =
                    BpmnAutoFixResult(
                        changed = true,
                        xml = "<fixed/>",
                        applied = listOf(BpmnAutoFixChange("klm/name-01", "Task_1", "stripped")),
                    ),
            )
        val xsd = FakeXsdValidationPort(issues = emptyList())
        val parser = FakeXmlParser(parsed)
        val strategy = strategy(lint = lint, xsd = xsd, parser = parser)
        val context = contextOf(diagnostics = listOf(lintDiagnostic(rule = "klm/name-01")))

        val result = assertIs<BpmnRepairResult.Repaired>(strategy.repair(context))
        assertEquals(parsed, result.definition)
        assertEquals(1, xsd.calls)
        assertEquals(1, parser.calls)
        assertEquals(1, lint.autoFixCalls)
    }

    @Test
    fun `auto-fix that produces XSD-invalid XML returns NotApplicable and skips parsing`() {
        val parser = FakeXmlParser(otherValidDefinition())
        val lint =
            FakeLintingPort(
                autoFixResult =
                    BpmnAutoFixResult(
                        changed = true,
                        xml = "<broken/>",
                        applied = listOf(BpmnAutoFixChange("klm/name-01", "Task_1", "stripped")),
                    ),
            )
        val xsd = FakeXsdValidationPort(issues = listOf(XsdValidationIssue("cvc-something")))
        val strategy = strategy(lint = lint, xsd = xsd, parser = parser)
        val context = contextOf(diagnostics = listOf(lintDiagnostic(rule = "klm/name-01")))

        assertIs<BpmnRepairResult.NotApplicable>(strategy.repair(context))
        assertEquals(1, xsd.calls)
        assertEquals(0, parser.calls)
    }

    @Test
    fun `auto-fix errors return LocalAttemptedNoChange carrying failure records and skip XSD or parser`() {
        val parser = FakeXmlParser(otherValidDefinition())
        val xsd = FakeXsdValidationPort(issues = emptyList())
        val lint =
            FakeLintingPort(
                autoFixResult =
                    BpmnAutoFixResult(
                        changed = false,
                        xml = "<unchanged/>",
                        errors = listOf(BpmnAutoFixError("klm/name-01", "Task_1", "handler boom")),
                    ),
            )
        val strategy = strategy(lint = lint, xsd = xsd, parser = parser)
        val context = contextOf(diagnostics = listOf(lintDiagnostic(rule = "klm/name-01")))

        val result = assertIs<BpmnRepairResult.LocalAttemptedNoChange>(strategy.repair(context))
        val failure = result.outcome.failures.single()
        assertEquals(1, result.outcome.failures.size)
        assertEquals("klm/name-01", failure.rule)
        assertEquals("Task_1", failure.elementId)
        assertEquals("handler boom", failure.reason)
        assertEquals(0, xsd.calls)
        assertEquals(0, parser.calls)
    }

    @Test
    fun `skipped declared-local rule throws an internal consistency error`() {
        val lint =
            FakeLintingPort(
                autoFixResult =
                    BpmnAutoFixResult(
                        changed = false,
                        xml = "<unchanged/>",
                        skipped = listOf(BpmnAutoFixSkip("klm/name-01", "Task_1", "no handler")),
                    ),
            )
        val strategy = strategy(lint = lint)
        val context = contextOf(diagnostics = listOf(lintDiagnostic(rule = "klm/name-01")))

        assertFailsWith<IllegalStateException> { strategy.repair(context) }
    }

    @Test
    fun `skipped non-declared rule does not throw and falls through`() {
        val lint =
            FakeLintingPort(
                autoFixResult =
                    BpmnAutoFixResult(
                        changed = false,
                        xml = "<unchanged/>",
                        skipped = listOf(BpmnAutoFixSkip("bpmnlint/some-core-rule", null, "skipped")),
                    ),
            )
        val strategy = strategy(lint = lint)
        val context = contextOf(diagnostics = listOf(lintDiagnostic(rule = "klm/name-01")))

        assertIs<BpmnRepairResult.NotApplicable>(strategy.repair(context))
    }

    @Test
    fun `returns NotApplicable when evaluation has no raw lint issues`() {
        val strategy = strategy(lint = FakeLintingPort())
        val context =
            contextOf(
                diagnostics = listOf(lintDiagnostic(rule = "klm/name-01")),
                rawLintIssues = null,
            )

        assertIs<BpmnRepairResult.NotApplicable>(strategy.repair(context))
    }

    @Test
    fun `LOCAL_MODEL_FIX diagnostic without any LOCAL_XML_FIX returns NotApplicable`() {
        val strategy = strategy(lint = FakeLintingPort())
        val context =
            contextOf(diagnostics = listOf(lintDiagnostic(kind = RepairKind.LOCAL_MODEL_FIX)))

        assertIs<BpmnRepairResult.NotApplicable>(strategy.repair(context))
    }

    // ------------------------------------------------------------------ helpers

    private fun strategy(
        lint: BpmnLintingPort = FakeLintingPort(),
        xsd: BpmnXsdValidationPort = FakeXsdValidationPort(),
        parser: BpmnXmlParser = FakeXmlParser(otherValidDefinition()),
    ) = LintLocalRepairStrategy(lint, xsd, parser)

    private fun contextOf(
        diagnostics: List<BpmnDiagnostic>,
        rawLintIssues: List<LintIssue>? =
            diagnostics
                .filter { it.source == BpmnDiagnosticSource.LINT }
                .mapNotNull { d -> d.rule?.let { LintIssue(id = d.elementId, rule = it, message = d.message) } },
    ): BpmnRepairStrategyContext {
        val definition = validDefinition()
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
                rawLintIssues = rawLintIssues,
            )
        val attempt =
            BpmnRepairAttempt(
                attemptNumber = 1,
                repairAttempts = 0,
                graph = graph(definition),
                evaluation = evaluation,
                messages = emptyList(),
            )
        return BpmnRepairStrategyContext(
            attempt = attempt,
            promptRunner = FakeOperationContext().promptRunner(),
        )
    }

    private fun lintDiagnostic(
        rule: String = "klm/name-01",
        kind: RepairKind? = RepairKind.LOCAL_XML_FIX,
    ) = BpmnDiagnostic(
        source = BpmnDiagnosticSource.LINT,
        message = "violation",
        rule = rule,
        category = "error",
        elementId = "Task_1",
        kind = kind,
    )

    private fun validDefinition() =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Test Process",
            nodes =
                listOf(
                    BpmnNode("Start_1", "Start", NodeType.START_EVENT, BpmnBounds(80.0, 100.0, 36.0, 36.0)),
                    BpmnNode("Task_1", "Do work", NodeType.USER_TASK, BpmnBounds(200.0, 80.0, 100.0, 80.0)),
                    BpmnNode("End_1", "End", NodeType.END_EVENT, BpmnBounds(360.0, 100.0, 36.0, 36.0)),
                ),
            sequences =
                listOf(
                    BpmnEdge(
                        id = "Flow_1",
                        sourceRef = "Start_1",
                        targetRef = "Task_1",
                        waypoints = listOf(BpmnWaypoint(116.0, 118.0), BpmnWaypoint(200.0, 120.0)),
                    ),
                    BpmnEdge(
                        id = "Flow_2",
                        sourceRef = "Task_1",
                        targetRef = "End_1",
                        waypoints = listOf(BpmnWaypoint(300.0, 120.0), BpmnWaypoint(360.0, 118.0)),
                    ),
                ),
        )

    private fun otherValidDefinition() = validDefinition().copy(processName = "After Local Fix")

    private fun graph(definition: BpmnDefinition): LaidOutProcessGraph {
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

    private class FakeLintingPort(
        private val autoFixResult: BpmnAutoFixResult? =
            BpmnAutoFixResult(changed = false, xml = ""),
    ) : BpmnLintingPort {
        var autoFixCalls = 0
            private set

        override fun lint(
            bpmnXml: String,
            phase: BpmnLintPhase,
        ): List<LintIssue> = emptyList()

        override fun autoFix(
            bpmnXml: String,
            issues: List<LintIssue>,
            phase: BpmnLintPhase,
        ): BpmnAutoFixResult? {
            autoFixCalls++
            return autoFixResult
        }

        override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> = emptyMap()

        override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> = emptyMap()
    }

    private class FakeXsdValidationPort(
        private val issues: List<XsdValidationIssue> = emptyList(),
    ) : BpmnXsdValidationPort {
        var calls = 0
            private set

        override fun validateDetailed(bpmnXml: String): List<XsdValidationIssue> {
            calls++
            return issues
        }
    }

    private class FakeXmlParser(
        private val definition: BpmnDefinition,
    ) : BpmnXmlParser {
        var calls = 0
            private set

        override fun parse(xml: String): BpmnDefinition {
            calls++
            return definition
        }
    }
}
