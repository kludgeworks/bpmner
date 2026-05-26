/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.test.unit.FakeOperationContext
import dev.groknull.bpmner.api.RepairKind
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
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnPatchApplier
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSeverity
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnEvaluation
import dev.groknull.bpmner.validation.BpmnRuleMetadata
import dev.groknull.bpmner.validation.GlobalDiagnostics
import dev.groknull.bpmner.validation.LintIssue
import dev.groknull.bpmner.validation.RepairMetadata
import dev.groknull.bpmner.validation.RuleCatalogService
import dev.groknull.bpmner.validation.RuleCategoryMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Suppress("TooManyFunctions")
class DeterministicTopologyRepairStrategyTest {
    @Test
    fun `returns NotApplicable when no LOCAL_MODEL_FIX diagnostics are stamped`() {
        val strategy = strategy()
        val context = contextOf(diagnostics = listOf(lintDiagnostic(kind = RepairKind.LLM_MODEL_PATCH)))

        assertIs<BpmnRepairResult.NotApplicable>(strategy.repair(context))
    }

    @Test
    fun `returns NotApplicable when no handler is registered for the diagnostic fixHandler`() {
        val strategy = strategy(handlers = emptyList())
        val context =
            contextOf(
                diagnostics =
                listOf(
                    lintDiagnostic(
                        kind = RepairKind.LOCAL_MODEL_FIX,
                        fixHandler = "missing",
                    ),
                ),
            )

        assertIs<BpmnRepairResult.NotApplicable>(strategy.repair(context))
    }

    @Test
    fun `applies local model fix when handler is found`() {
        val handler = StubModelFixHandler("some-handler")
        val diag =
            BpmnDiagnostic(
                source = BpmnDiagnosticSource.LINT,
                message = "fix it",
                kind = RepairKind.LOCAL_MODEL_FIX,
                fixHandler = "some-handler",
                elementId = "Task_1",
            )
        val operationContext = FakeOperationContext()
        val ctx =
            contextOf(
                diagnostics = listOf(diag),
                operationContext = operationContext,
            )

        val result = strategy(handlers = listOf(handler)).repair(ctx)

        assertIs<BpmnRepairResult.Repaired>(result)
        assertEquals(1, result.localFixSummary?.modelApplied)
        assertEquals(
            0,
            operationContext.llmInvocations.size,
            "deterministic LOCAL_MODEL_FIX path must not invoke the LLM",
        )
    }

    @Test
    fun `passes rule staticConfig and replacementMap through to handler via HandlerConfig`() {
        val captured = CapturingHandler("captureHandler")
        val catalog =
            stubCatalog(
                rule(
                    id = "demo-rule",
                    staticConfig = mapOf("discouragedWords" to listOf("activity")),
                    replacementMap = mapOf("REQ" to "request"),
                ),
            )
        val diag =
            BpmnDiagnostic(
                source = BpmnDiagnosticSource.LINT,
                message = "fix it",
                rule = "bpmner/demo-rule",
                kind = RepairKind.LOCAL_MODEL_FIX,
                fixHandler = "captureHandler",
                elementId = "Task_1",
            )

        val ctx = contextOf(diagnostics = listOf(diag))
        strategy(handlers = listOf(captured), catalog = catalog).repair(ctx)

        val received = assertNotNull(captured.lastConfig, "Handler must receive a HandlerConfig from the strategy")
        assertEquals(mapOf("discouragedWords" to listOf("activity")), received.staticConfig)
        assertEquals(mapOf("REQ" to "request"), received.replacementMap)
    }

    @Test
    fun `falls back to empty HandlerConfig when rule is not in the catalog`() {
        val captured = CapturingHandler("captureHandler")
        val diag =
            BpmnDiagnostic(
                source = BpmnDiagnosticSource.LINT,
                message = "fix it",
                rule = "bpmner/unknown-rule",
                kind = RepairKind.LOCAL_MODEL_FIX,
                fixHandler = "captureHandler",
                elementId = "Task_1",
            )

        val ctx = contextOf(diagnostics = listOf(diag))
        strategy(handlers = listOf(captured), catalog = stubCatalog()).repair(ctx)

        val received = assertNotNull(captured.lastConfig)
        assertEquals(HandlerConfig.EMPTY, received)
    }

    @Test
    fun `falls back to empty staticConfig when the rule's staticConfig is not a Map`() {
        // Reviewer concern: a non-null `staticConfig` that isn't a Map (e.g. a JSON array)
        // would silently produce an empty HandlerConfig. The cast still fails safely, but the
        // strategy must surface the misconfiguration via a warn log.
        val captured = CapturingHandler("captureHandler")
        val catalog =
            stubCatalog(
                rule(
                    id = "bad-config-rule",
                    // A list at the staticConfig level — not a Map — simulates the broken Pkl shape.
                    staticConfig = listOf("not", "a", "map"),
                ),
            )
        val diag =
            BpmnDiagnostic(
                source = BpmnDiagnosticSource.LINT,
                message = "fix it",
                rule = "bpmner/bad-config-rule",
                kind = RepairKind.LOCAL_MODEL_FIX,
                fixHandler = "captureHandler",
                elementId = "Task_1",
            )

        val ctx = contextOf(diagnostics = listOf(diag))
        strategy(handlers = listOf(captured), catalog = catalog).repair(ctx)

        val received = assertNotNull(captured.lastConfig)
        assertNull(received.staticConfig, "Non-Map staticConfig must not survive the cast")
        // Note: we don't assert the warn log here — verifying SLF4J output requires test
        // appender wiring that the rest of this test class doesn't use. The behaviour we
        // care about (no silent crash, no leaked bad value) is covered above.
    }

    @Test
    fun `returns NotApplicable when the handler emits no ops`() {
        val handler = StubModelFixHandler("noop-handler", emitsOps = false)
        val diag =
            BpmnDiagnostic(
                source = BpmnDiagnosticSource.LINT,
                message = "fix it",
                kind = RepairKind.LOCAL_MODEL_FIX,
                fixHandler = "noop-handler",
                elementId = "Task_1",
            )

        val result = strategy(handlers = listOf(handler)).repair(contextOf(diagnostics = listOf(diag)))

        assertIs<BpmnRepairResult.NotApplicable>(result)
    }

    private fun strategy(
        handlers: List<BpmnLocalModelFixHandler> = emptyList(),
        catalog: RuleCatalogService = stubCatalog(),
    ) = DeterministicTopologyRepairStrategy(
        BpmnLocalModelFixHandlerRegistry(handlers),
        BpmnPatchApplier(),
        catalog,
    )

    private fun stubCatalog(vararg rules: BpmnRuleMetadata): RuleCatalogService = object : RuleCatalogService() {
        override fun getRule(id: String): BpmnRuleMetadata? = rules.firstOrNull { it.id == id }
    }

    private fun rule(
        id: String,
        staticConfig: Any? = null,
        replacementMap: Map<String, String>? = null,
    ) = BpmnRuleMetadata(
        id = id,
        name = id,
        category = RuleCategoryMetadata(name = "test", shortCode = "T"),
        slug = id,
        intent = "test",
        forModellers = "test",
        forAI = "test",
        targetElements = emptyList(),
        severity = "warning",
        errorMessages = mapOf("default" to "test violation"),
        staticConfig = staticConfig,
        hasTsImplementation = false,
        aliases = emptyList(),
        deprecated = false,
        replacedBy = emptyList(),
        deprecationReason = null,
        repair = RepairMetadata(replacementMap = replacementMap),
    )

    private fun contextOf(
        diagnostics: List<BpmnDiagnostic>,
        definition: BpmnDefinition = sampleDefinition(),
        operationContext: OperationContext = FakeOperationContext(),
    ): BpmnRepairStrategyContext {
        val attempt =
            BpmnRepairAttempt(
                attemptNumber = 1,
                repairAttempts = 0,
                graph = graph(definition),
                evaluation =
                BpmnEvaluation(
                    definition = definition,
                    rendered = renderedFrom(definition),
                    diagnostics = diagnostics,
                    globalDiagnostics = GlobalDiagnostics(diagnostics),
                    validatedXml = null,
                    rawLintIssues =
                    diagnostics
                        .filter { it.source == BpmnDiagnosticSource.LINT }
                        .mapNotNull { d -> d.rule?.let { LintIssue(id = d.elementId, rule = it, message = d.message) } },
                ),
                messages = emptyList(),
            )
        return BpmnRepairStrategyContext(
            attempt = attempt,
            request = BpmnRequest("do thing"),
            operationContext = operationContext,
        )
    }

    private fun lintDiagnostic(
        rule: String = "bpmner/name-01",
        kind: RepairKind? = RepairKind.LOCAL_MODEL_FIX,
        fixHandler: String? = null,
        elementId: String? = "Task_1",
    ) = BpmnDiagnostic(
        source = BpmnDiagnosticSource.LINT,
        message = "violation",
        rule = rule,
        severity = BpmnDiagnosticSeverity.ERROR,
        elementId = elementId,
        kind = kind,
        fixHandler = fixHandler,
    )

    private fun sampleDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Test Process",
        nodes =
        listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnUserTask("Task_1", "Do work"),
            BpmnEndEvent("End_1", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge(id = "Flow_1", sourceRef = "Start_1", targetRef = "Task_1"),
            BpmnEdge(id = "Flow_2", sourceRef = "Task_1", targetRef = "End_1"),
        ),
    )

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

    @Suppress("TooManyFunctions")
    private class StubModelFixHandler(
        override val handlerName: String,
        private val emitsOps: Boolean = true,
    ) : BpmnLocalModelFixHandler {
        override fun buildPatch(
            definition: BpmnDefinition,
            elementId: String,
            config: HandlerConfig,
        ): List<BpmnPatchOperation> {
            if (!emitsOps) return emptyList()
            return listOf(
                BpmnPatchOperation(
                    type = BpmnPatchOperationType.SET_NODE_NAME,
                    nodeId = elementId,
                    name = "renamed",
                ),
            )
        }
    }

    private class CapturingHandler(
        override val handlerName: String,
    ) : BpmnLocalModelFixHandler {
        var lastConfig: HandlerConfig? = null
            private set

        override fun buildPatch(
            definition: BpmnDefinition,
            elementId: String,
            config: HandlerConfig,
        ): List<BpmnPatchOperation> {
            lastConfig = config
            // Emit at least one op so the strategy proceeds through patch application; tests inspect
            // only the captured config, not the final result.
            return listOf(
                BpmnPatchOperation(
                    type = BpmnPatchOperationType.SET_NODE_NAME,
                    nodeId = elementId,
                    name = "captured",
                ),
            )
        }
    }
}
