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
import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnFingerprintService
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.LintIssue
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.OutlineMetrics
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.ProcessOutline
import dev.groknull.bpmner.core.ValidatedOutline
import dev.groknull.bpmner.core.XsdValidationIssue
import dev.groknull.bpmner.generation.internal.adapter.outbound.BpmnDefinitionToXmlConverter
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnPatchApplier
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnRepairPromptFactory
import dev.groknull.bpmner.validation.internal.domain.BpmnDefinitionValidator
import dev.groknull.bpmner.validation.internal.domain.BpmnDiagnosticNormalizer
import dev.groknull.bpmner.validation.internal.domain.BpmnEvaluationPipeline
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnLintService
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnXsdValidator
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
        val fingerprints = BpmnFingerprintService()
        val prompts = BpmnRepairPromptFactory(config, lint, fingerprints)
        val patchApplier = BpmnPatchApplier()
        val strategies = mutableListOf<BpmnRepairStrategy>(
            FullLlmRewriteRepairStrategy(prompts),
            LlmPatchRepairStrategy(prompts, patchApplier),
            DeterministicTopologyRepairStrategy(BpmnTopologyRepair(patchApplier)),
            TargetedLabelRepairStrategy(config, prompts, patchApplier),
        )

        AnnotationAwareOrderComparator.sort(strategies)

        assertEquals(
            listOf(
                DeterministicTopologyRepairStrategy::class,
                TargetedLabelRepairStrategy::class,
                LlmPatchRepairStrategy::class,
                FullLlmRewriteRepairStrategy::class,
            ),
            strategies.map { it::class },
        )
    }

    @Test
    fun `deterministic topology strategy repairs without calling LLM`() {
        val invalid = joinForkDefinition()
        val xsd = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lint = RecordingLintService(
            listOf(
                listOf(
                    LintIssue(
                        id = "Gateway_1",
                        rule = "klm/no-gateway-join-fork",
                        message = "Gateway joins and forks at the same point",
                    )
                ),
                emptyList(),
            )
        )
        val converter = RecordingConverter()
        val engine = refinementEngine(BpmnConfig(maxAttempts = 3), lint, xsd, converter)
        val context = FakeActionContext()

        val result = engine.refine(BpmnRequest("Route an order"), graph(invalid), converter.render(invalid), context)

        assertEquals(1, result.repairAttempts)
        assertTrue(context.llmInvocations.isEmpty())
        assertEquals(2, xsd.xmls.size)
        assertEquals(2, lint.xmls.size)
    }

    @Test
    fun `evaluation exits before XSD and lint when graph validation fails`() {
        val invalid = validDefinition().copy(
            sequences = validDefinition().sequences.map {
                if (it.id == "Flow_1") it.copy(sourceRef = "Missing_Start") else it
            }
        )
        val xsd = RecordingXsdValidator(listOf(emptyList()))
        val lint = RecordingLintService(listOf(emptyList()))
        val normalizer = BpmnDiagnosticNormalizer()
        val pipeline = BpmnEvaluationPipeline(
            config = BpmnConfig(),
            bpmnLintingPort = lint,
            bpmnXsdValidationPort = xsd,
            bpmnDefinitionValidator = BpmnDefinitionValidator(),
            normalizer = normalizer,
            fingerprints = BpmnFingerprintService(),
        )

        val evaluation = pipeline.evaluate(
            graph = graph(invalid),
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
    ): BpmnRefinementEngine {
        val fingerprints = BpmnFingerprintService()
        val normalizer = BpmnDiagnosticNormalizer()
        val promptFactory = BpmnRepairPromptFactory(config, lintService, fingerprints)
        val patchApplier = BpmnPatchApplier()
        return BpmnRefinementEngine(
            config = config,
            bpmnRenderer = converter,
            validator = BpmnEvaluationPipeline(
                config = config,
                bpmnLintingPort = lintService,
                bpmnXsdValidationPort = xsdValidator,
                bpmnDefinitionValidator = BpmnDefinitionValidator(),
                normalizer = normalizer,
                fingerprints = fingerprints,
            ),
            promptFactory = promptFactory,
            fingerprints = fingerprints,
            strategies = listOf(
                DeterministicTopologyRepairStrategy(BpmnTopologyRepair(patchApplier)),
                TargetedLabelRepairStrategy(config, promptFactory, patchApplier),
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
        private val responses: List<List<LintIssue>?>,
    ) : BpmnLintService() {
        val xmls = mutableListOf<String>()
        private var index = 0

        override fun lint(bpmnXml: String, phase: dev.groknull.bpmner.core.BpmnLintPhase): List<LintIssue>? {
            xmls += bpmnXml
            return responses[index++]
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
    ) : ActionContext, OperationContext by delegate {
        override val processContext = delegate.processContext
        override val action: Action? = null
        override val toolGroups: Set<ToolGroupRequirement>
            get() = delegate.toolGroups
        override val operation = delegate.operation

        val llmInvocations
            get() = delegate.llmInvocations

        override fun promptRunner(
            llm: LlmOptions,
            toolGroups: Set<ToolGroupRequirement>,
            toolObjects: List<ToolObject>,
            promptContributors: List<PromptContributor>,
            contextualPromptContributors: List<ContextualPromptElement>,
            generateExamples: Boolean,
        ): PromptRunner = delegate.promptRunner(
            llm = llm,
            toolGroups = toolGroups,
            toolObjects = toolObjects,
            promptContributors = promptContributors,
            contextualPromptContributors = contextualPromptContributors,
            generateExamples = generateExamples,
        )
    }

    private fun graph(definition: BpmnDefinition): LaidOutProcessGraph {
        val owner = "phase:main"
        val objectOwners = buildMap {
            put("process", owner)
            definition.nodes.forEach { put("nodes[id=${it.id}]", owner) }
            definition.sequences.forEach { put("sequences[id=${it.id}]", owner) }
        }
        val composed = ComposedProcessGraph(
            outline = ValidatedOutline(ProcessOutline(BpmnRequest("test"), definition, OutlineMetrics(1, 0, 0, 0))),
            definition = definition,
            objectOwnersByObjectRef = objectOwners,
        )
        val elementOwners = buildMap {
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

    private fun validDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "Process_Test",
        processName = "Test",
        nodes = listOf(
            BpmnNode("StartEvent_1", "Started", NodeType.START_EVENT, BpmnBounds(80.0, 120.0, 36.0, 36.0)),
            BpmnNode("Task_1", "Do work", NodeType.USER_TASK, BpmnBounds(180.0, 98.0, 100.0, 80.0)),
            BpmnNode("EndEvent_1", "Done", NodeType.END_EVENT, BpmnBounds(320.0, 120.0, 36.0, 36.0)),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "StartEvent_1", "Task_1", waypoints = listOf(BpmnWaypoint(116.0, 138.0), BpmnWaypoint(180.0, 138.0))),
            BpmnEdge("Flow_2", "Task_1", "EndEvent_1", waypoints = listOf(BpmnWaypoint(280.0, 138.0), BpmnWaypoint(320.0, 138.0))),
        ),
    )

    private fun joinForkDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "Process_JoinFork",
        processName = "Join fork",
        nodes = listOf(
            BpmnNode("StartEvent_1", "A", NodeType.START_EVENT, BpmnBounds(40.0, 80.0, 36.0, 36.0)),
            BpmnNode("StartEvent_2", "B", NodeType.START_EVENT, BpmnBounds(40.0, 180.0, 36.0, 36.0)),
            BpmnNode("Gateway_1", "Route?", NodeType.EXCLUSIVE_GATEWAY, BpmnBounds(160.0, 130.0, 50.0, 50.0)),
            BpmnNode("Task_1", "Do one", NodeType.SERVICE_TASK, BpmnBounds(280.0, 80.0, 100.0, 80.0)),
            BpmnNode("Task_2", "Do two", NodeType.SERVICE_TASK, BpmnBounds(280.0, 180.0, 100.0, 80.0)),
            BpmnNode("EndEvent_1", "Done", NodeType.END_EVENT, BpmnBounds(440.0, 130.0, 36.0, 36.0)),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "StartEvent_1", "Gateway_1", waypoints = listOf(BpmnWaypoint(76.0, 98.0), BpmnWaypoint(160.0, 155.0))),
            BpmnEdge("Flow_2", "StartEvent_2", "Gateway_1", waypoints = listOf(BpmnWaypoint(76.0, 198.0), BpmnWaypoint(160.0, 155.0))),
            BpmnEdge("Flow_3", "Gateway_1", "Task_1", waypoints = listOf(BpmnWaypoint(210.0, 155.0), BpmnWaypoint(280.0, 120.0))),
            BpmnEdge("Flow_4", "Gateway_1", "Task_2", waypoints = listOf(BpmnWaypoint(210.0, 155.0), BpmnWaypoint(280.0, 220.0))),
            BpmnEdge("Flow_5", "Task_1", "EndEvent_1", waypoints = listOf(BpmnWaypoint(380.0, 120.0), BpmnWaypoint(440.0, 148.0))),
            BpmnEdge("Flow_6", "Task_2", "EndEvent_1", waypoints = listOf(BpmnWaypoint(380.0, 220.0), BpmnWaypoint(440.0, 148.0))),
        ),
    )
}
