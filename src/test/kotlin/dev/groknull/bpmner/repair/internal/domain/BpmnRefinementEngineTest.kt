package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.ContextualPromptElement
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.core.Action
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.TestBpmnFixtures.testBpmnDefinition
import dev.groknull.bpmner.TestBpmnFixtures.testLaidOutGraph
import dev.groknull.bpmner.core.BpmnAutoFixChange
import dev.groknull.bpmner.core.BpmnAutoFixResult
import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnFingerprintService
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.BpmnLintRuleCapability
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnRepairSafety
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.LintIssue
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.RepairKind
import dev.groknull.bpmner.core.XsdValidationIssue
import dev.groknull.bpmner.generation.BpmnXmlParser
import dev.groknull.bpmner.generation.internal.adapter.outbound.BpmnDefinitionToXmlConverter
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnPatchApplier
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnRepairPromptFactory
import dev.groknull.bpmner.repair.internal.domain.handlers.BypassGatewayHandler
import dev.groknull.bpmner.repair.internal.domain.handlers.ConvergingGatewayClearNameHandler
import dev.groknull.bpmner.repair.internal.domain.handlers.InsertConvergingGatewayHandler
import dev.groknull.bpmner.repair.internal.domain.handlers.SplitJoinForkGatewayHandler
import dev.groknull.bpmner.validation.BpmnRuleGuidancePort
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnLintJsEngine
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnLintService
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnXsdValidator
import dev.groknull.bpmner.validation.internal.adapter.outbound.PklRuleCapabilityAdapter
import dev.groknull.bpmner.validation.internal.adapter.outbound.RuleCatalogService
import dev.groknull.bpmner.validation.internal.domain.BpmnDefinitionValidator
import dev.groknull.bpmner.validation.internal.domain.BpmnDiagnosticNormalizer
import dev.groknull.bpmner.validation.internal.domain.BpmnEvaluationPipeline
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.annotation.AnnotationAwareOrderComparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BpmnRefinementEngineTest {
    @Test
    fun `strategy annotations order deterministic repairs before LLM repairs`() {
        val config = BpmnConfig()
        val lint = RecordingLintService(listOf(emptyList()))
        val xsd = RecordingXsdValidator(listOf(emptyList()))
        val parser = RecordingXmlParser(validDefinition())
        val fingerprints = BpmnFingerprintService()
        val prompts = BpmnRepairPromptFactory(lint, fingerprints, NoopRuleGuidancePort)
        val patchApplier = BpmnPatchApplier()
        val strategies =
            mutableListOf<BpmnRepairStrategy>(
                FullLlmRewriteRepairStrategy(prompts),
                LlmPatchRepairStrategy(prompts, patchApplier),
                LintLocalRepairStrategy(
                    lint,
                    xsd,
                    parser,
                    BpmnLocalModelFixHandlerRegistry(emptyList()),
                    patchApplier,
                ),
            )

        AnnotationAwareOrderComparator.sort(strategies)

        assertEquals(
            listOf(
                LintLocalRepairStrategy::class,
                LlmPatchRepairStrategy::class,
                FullLlmRewriteRepairStrategy::class,
            ),
            strategies.map { it::class },
        )
    }

    @Test
    fun `lint local strategy resolves LOCAL_XML diagnostic without calling LLM`() {
        val initial = validDefinition()
        val corrected = initial.copy(processName = "Test corrected")
        val lintIssue =
            LintIssue(
                id = "Task_1",
                rule = "klm/name-01",
                message = "Element name must not include its BPMN element type",
            )
        val capability =
            BpmnLintRuleCapability(
                id = "name-01",
                kind = RepairKind.LOCAL_XML_FIX,
                repairSafety = BpmnRepairSafety.SAFE_AUTOMATIC,
                fixHandler = "stripTypeWords",
                handlerExists = true,
                replacementMap = null,
            )
        val lint =
            RecordingLintService(
                lintResponses = listOf(listOf(lintIssue), emptyList()),
                autoFixResponse =
                    BpmnAutoFixResult(
                        changed = true,
                        xml = "<bpmn:locally-fixed/>",
                        applied = listOf(BpmnAutoFixChange("klm/name-01", "Task_1", "stripped")),
                    ),
                capabilities = mapOf("name-01" to capability),
            )
        val xsd = RecordingXsdValidator(listOf(emptyList(), emptyList(), emptyList()))
        val parser = RecordingXmlParser(corrected)
        val engine =
            refinementEngine(
                config = BpmnConfig(maxAttempts = 3),
                lintService = lint,
                xsdValidator = xsd,
                converter = RecordingConverter(),
                xmlParser = parser,
            )
        val context = FakeActionContext()

        val result =
            engine.refine(
                BpmnRequest("Generate a process"),
                testLaidOutGraph(initial),
                RecordingConverter().render(initial),
                context,
            )

        assertEquals(1, result.repairAttempts)
        assertTrue(context.llmInvocations.isEmpty())
        assertEquals(1, lint.autoFixCalls)
        assertEquals(1, parser.parseCalls)
    }

    @Test
    fun `mixed local-failed and LLM diagnostics route LLM with annotated context`() {
        val initial = validDefinition()
        val corrected = initial.copy(processName = "Test corrected")
        val lint = mixedDiagnosticLintService()
        val xsd = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val parser = RecordingXmlParser(corrected)
        val engine =
            refinementEngine(
                config = BpmnConfig(maxAttempts = 3),
                lintService = lint,
                xsdValidator = xsd,
                converter = RecordingConverter(),
                xmlParser = parser,
            )
        val context = FakeActionContext()
        context.expectResponse(setNodeNamePatch(nodeId = "EndEvent_1", name = "Order completed"))

        engine.refine(
            BpmnRequest("Generate a process"),
            testLaidOutGraph(initial),
            RecordingConverter().render(initial),
            context,
        )

        assertEquals(1, context.llmInvocations.size, "expected exactly one LLM call after local repair fell through")
        val prompt =
            context.llmInvocations
                .single()
                .messages
                .joinToString("\n") { it.content }
        assertTrue(prompt.contains("rule=klm/name-02"), "LLM diagnostic should appear in prompt")
        assertTrue(prompt.contains("rule=klm/name-01"), "failed-local diagnostic should appear in prompt")
        assertTrue(
            prompt.contains("[local-fix-failed: handler boom]"),
            "failed-local diagnostic should be annotated; got: $prompt",
        )
        assertEquals(1, lint.autoFixCalls)
    }

    private fun mixedDiagnosticLintService(): RecordingLintService {
        val localLintIssue =
            LintIssue(id = "Task_1", rule = "klm/name-01", message = "Element name must not include its BPMN element type")
        val llmLintIssue =
            LintIssue(id = "EndEvent_1", rule = "klm/name-02", message = "Use a verb-object name")
        val localCapability =
            BpmnLintRuleCapability(
                id = "name-01",
                kind = RepairKind.LOCAL_XML_FIX,
                repairSafety = BpmnRepairSafety.SAFE_AUTOMATIC,
                fixHandler = "stripTypeWords",
                handlerExists = true,
                replacementMap = null,
            )
        val autoFixError =
            dev.groknull.bpmner.core
                .BpmnAutoFixError(rule = "klm/name-01", elementId = "Task_1", message = "handler boom")
        return RecordingLintService(
            lintResponses = listOf(listOf(localLintIssue, llmLintIssue), emptyList()),
            autoFixResponse = BpmnAutoFixResult(changed = false, xml = "", errors = listOf(autoFixError)),
            capabilities = mapOf("name-01" to localCapability),
        )
    }

    private fun setNodeNamePatch(
        nodeId: String,
        name: String,
    ): BpmnRepairPatch =
        BpmnRepairPatch(
            operations =
                listOf(BpmnPatchOperation(type = BpmnPatchOperationType.SET_NODE_NAME, nodeId = nodeId, name = name)),
        )

    @Test
    fun `local model fix strategy repairs topology diagnostic without calling LLM`() {
        val invalid = joinForkDefinition()
        val xsd = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val topologyCapability =
            BpmnLintRuleCapability(
                id = "no-gateway-join-fork",
                kind = RepairKind.LOCAL_MODEL_FIX,
                repairSafety = BpmnRepairSafety.SAFE_AUTOMATIC,
                fixHandler = "splitJoinForkGateway",
                handlerExists = true,
                replacementMap = null,
            )
        val lint =
            RecordingLintService(
                lintResponses =
                    listOf(
                        listOf(
                            LintIssue(
                                id = "Gateway_1",
                                rule = "klm/no-gateway-join-fork",
                                message = "Gateway joins and forks at the same point",
                            ),
                        ),
                        emptyList(),
                    ),
                capabilities = mapOf("no-gateway-join-fork" to topologyCapability),
            )
        val converter = RecordingConverter()
        val engine = refinementEngine(BpmnConfig(maxAttempts = 3), lint, xsd, converter)
        val context = FakeActionContext()

        val result = engine.refine(BpmnRequest("Route an order"), testLaidOutGraph(invalid), converter.render(invalid), context)

        assertEquals(1, result.repairAttempts)
        assertTrue(context.llmInvocations.isEmpty())
        assertEquals(2, xsd.xmls.size)
        assertEquals(2, lint.xmls.size)
    }

    @Test
    fun `evaluation exits before XSD and lint when graph validation fails`() {
        val invalid =
            validDefinition().copy(
                sequences =
                    validDefinition().sequences.map {
                        if (it.id == "Flow_1") it.copy(sourceRef = "Missing_Start") else it
                    },
            )
        val xsd = RecordingXsdValidator(listOf(emptyList()))
        val lint = RecordingLintService(listOf(emptyList()))
        val normalizer = BpmnDiagnosticNormalizer(lint)
        val pipeline =
            BpmnEvaluationPipeline(
                config = BpmnConfig(),
                bpmnLintingPort = lint,
                bpmnXsdValidationPort = xsd,
                bpmnDefinitionValidator = BpmnDefinitionValidator(),
                normalizer = normalizer,
                fingerprints = BpmnFingerprintService(),
            )

        val evaluation =
            pipeline.evaluate(
                graph = testLaidOutGraph(invalid),
                definition = invalid,
                rendered = null,
                repairAttempts = 0,
            )

        assertTrue(evaluation.diagnostics.any { it.source == BpmnDiagnosticSource.GRAPH })
        assertEquals(0, xsd.xmls.size)
        assertEquals(0, lint.xmls.size)
    }

    private fun refinementEngine(
        config: BpmnConfig,
        lintService: BpmnLintService,
        xsdValidator: BpmnXsdValidator,
        converter: BpmnDefinitionToXmlConverter,
        xmlParser: BpmnXmlParser = RecordingXmlParser(validDefinition()),
    ): BpmnRefinementEngine {
        val fingerprints = BpmnFingerprintService()
        val normalizer = BpmnDiagnosticNormalizer(lintService)
        val promptFactory = BpmnRepairPromptFactory(lintService, fingerprints, NoopRuleGuidancePort)
        val patchApplier = BpmnPatchApplier()
        return BpmnRefinementEngine(
            config = config,
            bpmnRenderer = converter,
            validator =
                BpmnEvaluationPipeline(
                    config = config,
                    bpmnLintingPort = lintService,
                    bpmnXsdValidationPort = xsdValidator,
                    bpmnDefinitionValidator = BpmnDefinitionValidator(),
                    normalizer = normalizer,
                    fingerprints = fingerprints,
                ),
            promptFactory = promptFactory,
            fingerprints = fingerprints,
            strategies =
                listOf(
                    LintLocalRepairStrategy(
                        lintService,
                        xsdValidator,
                        xmlParser,
                        BpmnLocalModelFixHandlerRegistry(
                            listOf(
                                SplitJoinForkGatewayHandler(),
                                InsertConvergingGatewayHandler(),
                                BypassGatewayHandler(),
                                ConvergingGatewayClearNameHandler(),
                            ),
                        ),
                        patchApplier,
                    ),
                    LlmPatchRepairStrategy(promptFactory, patchApplier),
                    FullLlmRewriteRepairStrategy(promptFactory),
                ),
            eventPublisher = NoOpEventPublisher,
        )
    }

    private object NoOpEventPublisher : ApplicationEventPublisher {
        override fun publishEvent(event: Any) = Unit
    }

    private class RecordingLintService(
        lintResponses: List<List<LintIssue>?>,
        private val autoFixResponse: BpmnAutoFixResult? = null,
        private val capabilities: Map<String, BpmnLintRuleCapability> = emptyMap(),
    ) : BpmnLintService(
            catalogService = RuleCatalogService(),
            engine = BpmnLintJsEngine(),
            pklAdapter = PklRuleCapabilityAdapter(RuleCatalogService()),
        ) {
        val xmls = mutableListOf<String>()
        private var _autoFixCalls = 0
        val autoFixCalls: Int get() = _autoFixCalls
        private val responses = lintResponses
        private var index = 0

        override fun lint(
            bpmnXml: String,
            phase: BpmnLintPhase,
        ): List<LintIssue>? {
            xmls += bpmnXml
            return responses[index++]
        }

        override fun autoFix(
            bpmnXml: String,
            issues: List<LintIssue>,
            phase: BpmnLintPhase,
        ): BpmnAutoFixResult? {
            _autoFixCalls++
            return autoFixResponse
        }

        override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> = capabilities
    }

    private class RecordingXmlParser(
        private val definition: BpmnDefinition,
    ) : BpmnXmlParser {
        var parseCalls = 0
            private set

        override fun parse(xml: String): BpmnDefinition {
            parseCalls++
            return definition
        }
    }

    private class RecordingXsdValidator(
        private val responses: List<List<XsdValidationIssue>>,
    ) : BpmnXsdValidator() {
        val xmls = mutableListOf<String>()
        private var index = 0

        override fun validateDetailed(bpmnXml: String): List<XsdValidationIssue> {
            xmls += bpmnXml
            return responses[index++]
        }
    }

    private class RecordingConverter : BpmnDefinitionToXmlConverter()

    private class FakeActionContext(
        private val delegate: FakeOperationContext = FakeOperationContext(),
    ) : ActionContext,
        OperationContext by delegate {
        override val processContext = delegate.processContext
        override val action: Action? = null
        override val toolGroups: Set<ToolGroupRequirement>
            get() = delegate.toolGroups
        override val operation = delegate.operation

        val llmInvocations
            get() = delegate.llmInvocations

        fun expectResponse(response: Any) {
            delegate.expectResponse(response)
        }

        override fun promptRunner(
            llm: LlmOptions,
            toolGroups: Set<ToolGroupRequirement>,
            toolObjects: List<ToolObject>,
            promptContributors: List<PromptContributor>,
            contextualPromptContributors: List<ContextualPromptElement>,
            generateExamples: Boolean,
        ): PromptRunner =
            delegate.promptRunner(
                llm = llm,
                toolGroups = toolGroups,
                toolObjects = toolObjects,
                promptContributors = promptContributors,
                contextualPromptContributors = contextualPromptContributors,
                generateExamples = generateExamples,
            )
    }

    private fun validDefinition(): BpmnDefinition =
        testBpmnDefinition(
            processId = "Process_Test",
            processName = "Test",
            nodeType = NodeType.USER_TASK,
            startName = "Started",
            taskName = "Do work",
            endName = "Done",
        )

    private fun joinForkDefinition(): BpmnDefinition =
        BpmnDefinition(
            processId = "Process_JoinFork",
            processName = "Join fork",
            nodes =
                listOf(
                    BpmnNode("StartEvent_1", "A", NodeType.START_EVENT, BpmnBounds(40.0, 80.0, 36.0, 36.0)),
                    BpmnNode("StartEvent_2", "B", NodeType.START_EVENT, BpmnBounds(40.0, 180.0, 36.0, 36.0)),
                    BpmnNode("Gateway_1", "Route?", NodeType.EXCLUSIVE_GATEWAY, BpmnBounds(160.0, 130.0, 50.0, 50.0)),
                    BpmnNode("Task_1", "Do one", NodeType.SERVICE_TASK, BpmnBounds(280.0, 80.0, 100.0, 80.0)),
                    BpmnNode("Task_2", "Do two", NodeType.SERVICE_TASK, BpmnBounds(280.0, 180.0, 100.0, 80.0)),
                    BpmnNode("EndEvent_1", "Done", NodeType.END_EVENT, BpmnBounds(440.0, 130.0, 36.0, 36.0)),
                ),
            sequences =
                listOf(
                    BpmnEdge(
                        "Flow_1",
                        "StartEvent_1",
                        "Gateway_1",
                        waypoints = listOf(BpmnWaypoint(76.0, 98.0), BpmnWaypoint(160.0, 155.0)),
                    ),
                    BpmnEdge(
                        "Flow_2",
                        "StartEvent_2",
                        "Gateway_1",
                        waypoints = listOf(BpmnWaypoint(76.0, 198.0), BpmnWaypoint(160.0, 155.0)),
                    ),
                    BpmnEdge(
                        "Flow_3",
                        "Gateway_1",
                        "Task_1",
                        waypoints = listOf(BpmnWaypoint(210.0, 155.0), BpmnWaypoint(280.0, 120.0)),
                    ),
                    BpmnEdge(
                        "Flow_4",
                        "Gateway_1",
                        "Task_2",
                        waypoints = listOf(BpmnWaypoint(210.0, 155.0), BpmnWaypoint(280.0, 220.0)),
                    ),
                    BpmnEdge(
                        "Flow_5",
                        "Task_1",
                        "EndEvent_1",
                        waypoints = listOf(BpmnWaypoint(380.0, 120.0), BpmnWaypoint(440.0, 148.0)),
                    ),
                    BpmnEdge(
                        "Flow_6",
                        "Task_2",
                        "EndEvent_1",
                        waypoints = listOf(BpmnWaypoint(380.0, 220.0), BpmnWaypoint(440.0, 148.0)),
                    ),
                ),
        )

    private object NoopRuleGuidancePort : BpmnRuleGuidancePort {
        override fun getLlmRuleGuidance(): String = ""
    }
}
