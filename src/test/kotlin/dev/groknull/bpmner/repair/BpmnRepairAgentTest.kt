package dev.groknull.bpmner.repair

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.ContextualPromptElement
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionRetryPolicy
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.core.BpmnAutoFixResult
import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnFingerprintService
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnValidatorInfrastructureException
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.LintIssue
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.OutlineMetrics
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.ProcessOutline
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.core.ValidatedOutline
import dev.groknull.bpmner.core.XsdValidationIssue
import dev.groknull.bpmner.generation.internal.BpmnDefinitionToXmlConverter
import dev.groknull.bpmner.repair.internal.BpmnPatchApplier
import dev.groknull.bpmner.repair.internal.BpmnPatchOperation
import dev.groknull.bpmner.repair.internal.BpmnPatchOperationType
import dev.groknull.bpmner.repair.internal.BpmnRefinementEngine
import dev.groknull.bpmner.repair.internal.BpmnRepairPatch
import dev.groknull.bpmner.repair.internal.BpmnRepairPromptFactory
import dev.groknull.bpmner.repair.internal.BpmnTopologyRepair
import dev.groknull.bpmner.repair.internal.DeterministicTopologyRepairStrategy
import dev.groknull.bpmner.repair.internal.FullLlmRewriteRepairStrategy
import dev.groknull.bpmner.repair.internal.LlmPatchRepairStrategy
import dev.groknull.bpmner.repair.internal.PatchApplicationResult
import dev.groknull.bpmner.repair.internal.TargetedLabelRepairStrategy
import dev.groknull.bpmner.validation.internal.BpmnDefinitionValidator
import dev.groknull.bpmner.validation.internal.BpmnDiagnosticNormalizer
import dev.groknull.bpmner.validation.internal.BpmnEvaluationPipeline
import dev.groknull.bpmner.validation.internal.BpmnLintService
import dev.groknull.bpmner.validation.internal.BpmnXsdValidator
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BpmnRepairAgentTest {

    private fun buildRepairAgent(
        config: BpmnConfig,
        lintService: BpmnLintService,
        xsdValidator: BpmnXsdValidator,
        converter: BpmnDefinitionToXmlConverter,
        patchApplier: BpmnPatchApplier = BpmnPatchApplier(),
    ): BpmnRepairAgent {
        val fingerprints = BpmnFingerprintService()
        val normalizer = BpmnDiagnosticNormalizer()
        val promptFactory = BpmnRepairPromptFactory(config, lintService, fingerprints)
        val evaluationPipeline = BpmnEvaluationPipeline(
            config = config,
            bpmnLintService = lintService,
            bpmnXsdValidator = xsdValidator,
            bpmnDefinitionValidator = BpmnDefinitionValidator(),
            normalizer = normalizer,
            fingerprints = fingerprints,
        )
        val strategies = listOf(
            DeterministicTopologyRepairStrategy(BpmnTopologyRepair(), patchApplier),
            TargetedLabelRepairStrategy(patchApplier, promptFactory),
            LlmPatchRepairStrategy(patchApplier, promptFactory),
            FullLlmRewriteRepairStrategy(promptFactory, fingerprints),
        )
        val refinementEngine = BpmnRefinementEngine(
            config = config,
            bpmnRenderer = converter,
            validator = evaluationPipeline,
            promptFactory = promptFactory,
            fingerprints = fingerprints,
            strategies = strategies,
            eventPublisher = NoOpEventPublisher,
        )
        return BpmnRepairAgent(refinementEngine)
    }

    @Test
    fun `valid rendered bpmn passes straight through to validated xml`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService = RecordingLintService(listOf(emptyList()))
        val converter = RecordingConverter()
        val agent = buildRepairAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter)
        val definition = validDefinition()
        val rendered = converter.render(definition)
        val context = FakeActionContext()

        val result = agent.validateAndRefineBpmn(BpmnRequest("Make toast"), graph(definition), rendered, context)

        assertEquals(rendered.xml, result.xml)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(0, result.repairAttempts)
        assertEquals(1, xsdValidator.xmls.size)
        assertEquals(1, lintService.xmls.size)
        assertEquals(listOf(BpmnLintPhase.SEMANTIC_PRE_LAYOUT), lintService.phases)
        assertEquals(1, converter.renderCalls)
    }

    @Test
    fun `semantic repair uses pre-layout lint phase`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService = RecordingLintService(listOf(emptyList()))
        val converter = RecordingConverter()
        val agent = buildRepairAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter)
        val definition = validDefinition()

        agent.validateAndRefineBpmn(BpmnRequest("Make toast"), graph(definition), converter.render(definition), FakeActionContext())

        assertEquals(listOf(BpmnLintPhase.SEMANTIC_PRE_LAYOUT), lintService.phases)
    }

    @Test
    fun `lint issue with linked id triggers definition repair and full rerender revalidation`() {
        val invalid = validDefinition()
        val corrected = validDefinition(processName = "Make toast correctly")
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintService = RecordingLintService(
            listOf(
                listOf(LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")),
                emptyList(),
            )
        )
        val converter = RecordingConverter()
        val agent = buildRepairAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(corrected)
        val initialRendered = converter.render(invalid)

        val result = agent.validateAndRefineBpmn(BpmnRequest("Make toast"), graph(invalid), initialRendered, context)

        assertTrue(result.xml.contains("id=\"Process_MakeToast\""))
        assertTrue(result.xml.contains("id=\"Task_1\""))
        assertEquals(2, xsdValidator.xmls.size)
        assertEquals(2, lintService.xmls.size)
        assertEquals(2, converter.renderCalls)
        assertEquals(1, result.repairAttempts)
        val repairPrompt = context.llmInvocations.single().messages.joinToString("\n") { it.content }
        assertTrue(repairPrompt.contains("elementId=Task_1"))
        assertTrue(repairPrompt.contains("objectRef=nodes[id=Task_1]"))
    }

    @Test
    fun `lint parse error for unknown rule aborts without repair`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService = RecordingLintService(
            listOf(
                listOf(
                    LintIssue(
                        id = null,
                        rule = "parse-error",
                        message = "unknown rule <bpmneract-01-verb-object-name>",
                    )
                )
            )
        )
        val converter = RecordingConverter()
        val agent = buildRepairAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        val definition = validDefinition()
        val initialRendered = converter.render(definition)

        val error = assertFailsWith<BpmnValidatorInfrastructureException> {
            agent.validateAndRefineBpmn(BpmnRequest("Make toast"), graph(definition), initialRendered, context)
        }

        assertTrue(error.message!!.contains("BPMN validator infrastructure failure"))
        assertTrue(error.message!!.contains("bpmneract-01-verb-object-name"))
        assertTrue(context.llmInvocations.isEmpty())
        assertEquals(1, xsdValidator.xmls.size)
        assertEquals(1, lintService.xmls.size)
        assertEquals(1, converter.renderCalls)
    }

    @Test
    fun `bpmner lint issue includes matching rule docs in repair prompt contributor`() {
        val invalid = validDefinition()
        val corrected = validDefinition(processName = "Make toast correctly")
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintService = RecordingLintService(
            responses = listOf(
                listOf(LintIssue(id = "Task_1", rule = "bpmner/gen-02-no-duplicate-diagrams", message = "Duplicate BPMNDiagram")),
                emptyList(),
            ),
            docs = mapOf(
                "bpmner/gen-02-no-duplicate-diagrams" to "# gen-02-no-duplicate-diagrams\n\nDiagram docs"
            ),
        )
        val converter = RecordingConverter()
        val agent = buildRepairAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(corrected)
        val initialRendered = converter.render(invalid)

        agent.validateAndRefineBpmn(BpmnRequest("Make toast"), graph(invalid), initialRendered, context)

        val promptContributions = context.llmInvocations.single().interaction.promptContributors.joinToString("\n") {
            it.contribution()
        }
        assertTrue(promptContributions.contains("BPMNER lint rule documentation for current violations"))
        assertTrue(promptContributions.contains("# gen-02-no-duplicate-diagrams"))
    }

    @Test
    fun `xsd issue is preserved as diagnostic and causes rerender before succeeding`() {
        val initial = validDefinition()
        val corrected = validDefinition(
            processId = "Process_Fixed",
            processName = "Prepare toast safely",
        )
        val xsdValidator = RecordingXsdValidator(
            listOf(
                listOf(XsdValidationIssue("cvc-complex-type failure near Task_1", "Task_1")),
                emptyList(),
            )
        )
        val lintService = RecordingLintService(listOf(emptyList()))
        val converter = RecordingConverter()
        val agent = buildRepairAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(corrected)
        val initialRendered = converter.render(initial)

        val result = agent.validateAndRefineBpmn(BpmnRequest("Make toast"), graph(initial), initialRendered, context)

        assertTrue(result.xml.contains("id=\"Process_Fixed\""))
        assertTrue(result.xml.contains("name=\"Prepare toast safely\""))
        assertEquals(2, xsdValidator.xmls.size)
        assertEquals(1, lintService.xmls.size)
        val repairPrompt = context.llmInvocations.single().messages.joinToString("\n") { it.content }
        assertTrue(repairPrompt.contains("source=xsd"))
        assertTrue(repairPrompt.contains("elementId=Task_1"))
        assertTrue(repairPrompt.contains("objectRef=nodes[id=Task_1]"))
    }

    @Test
    fun `workflow refinement still fails after configured max attempts`() {
        val initial = validDefinition()
        val corrected = validDefinition(processName = "Make toast again")
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintService = RecordingLintService(
            listOf(
                listOf(LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")),
                listOf(LintIssue(id = "Task_1", rule = "start-event-required", message = "Still missing start event")),
            )
        )
        val converter = RecordingConverter()
        val agent = buildRepairAgent(BpmnConfig(maxAttempts = 2), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(corrected)
        val initialRendered = converter.render(initial)

        val error = assertFailsWith<IllegalStateException> {
            agent.validateAndRefineBpmn(BpmnRequest("Make toast"), graph(initial), initialRendered, context)
        }

        assertTrue(error.message!!.contains("Failed to produce valid BPMN after 2 attempts"))
        assertEquals(2, xsdValidator.xmls.size)
        assertEquals(2, lintService.xmls.size)
    }

    @Test
    fun `unchanged diagnostics fail fast after one repair cycle`() {
        val initial = validDefinition()
        val corrected = validDefinition(processName = "Make toast again")
        val repeatedIssue = LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintService = RecordingLintService(listOf(listOf(repeatedIssue), listOf(repeatedIssue)))
        val converter = RecordingConverter()
        val agent = buildRepairAgent(BpmnConfig(maxAttempts = 5), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(corrected)
        val initialRendered = converter.render(initial)

        val error = assertFailsWith<IllegalStateException> {
            agent.validateAndRefineBpmn(BpmnRequest("Make toast"), graph(initial), initialRendered, context)
        }

        assertTrue(error.message!!.contains("unchanged diagnostics"))
        assertTrue(error.message!!.contains("history=#1"))
        assertEquals(1, context.llmInvocations.size)
        assertEquals(2, xsdValidator.xmls.size)
        assertEquals(2, lintService.xmls.size)
    }

    @Test
    fun `unchanged repaired definition fails before rerender`() {
        val initial = validDefinition()
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService = RecordingLintService(
            listOf(listOf(LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")))
        )
        val converter = RecordingConverter()
        val agent = buildRepairAgent(BpmnConfig(maxAttempts = 5), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(initial)
        val initialRendered = converter.render(initial)

        val error = assertFailsWith<IllegalStateException> {
            agent.validateAndRefineBpmn(BpmnRequest("Make toast"), graph(initial), initialRendered, context)
        }

        assertTrue(error.message!!.contains("unchanged patch"))
        assertEquals(1, context.llmInvocations.size)
        assertEquals(1, xsdValidator.xmls.size)
        assertEquals(1, lintService.xmls.size)
        assertEquals(1, converter.renderCalls)
    }

    @Test
    fun `previous invalid definition output fails before another validation pass`() {
        val initial = validDefinition()
        val firstRepair = validDefinition(processName = "Make toast again")
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintService = RecordingLintService(
            listOf(
                listOf(LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")),
                listOf(LintIssue(id = "Task_1", rule = "end-event-required", message = "Missing end event")),
            )
        )
        val converter = RecordingConverter()
        val agent = buildRepairAgent(BpmnConfig(maxAttempts = 5), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(firstRepair)
        context.expectResponse(initial)
        val initialRendered = converter.render(initial)

        val error = assertFailsWith<IllegalStateException> {
            agent.validateAndRefineBpmn(BpmnRequest("Make toast"), graph(initial), initialRendered, context)
        }

        assertTrue(error.message!!.contains("repeated invalid output"))
        assertEquals(2, context.llmInvocations.size)
        assertEquals(2, xsdValidator.xmls.size)
        assertEquals(2, lintService.xmls.size)
        assertEquals(2, converter.renderCalls)
    }

    @Test
    fun `patchable lint diagnostic results in patch repair prompt and patch application`() {
        val patchableLintIssue = LintIssue(id = "Task_1", rule = "label-required", message = "Task label required")
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintService = RecordingLintService(listOf(listOf(patchableLintIssue), emptyList()))
        val converter = RecordingConverter()
        val patchedDefinition = validDefinition(processName = "Make toast — patched")
        val patchApplier = object : BpmnPatchApplier() {
            override fun apply(definition: BpmnDefinition, patch: BpmnRepairPatch): PatchApplicationResult =
                PatchApplicationResult.Success(patchedDefinition)
        }
        val agent = buildRepairAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter, patchApplier = patchApplier)
        val context = FakeActionContext()
        context.expectResponse(
            BpmnRepairPatch(
                operations = listOf(BpmnPatchOperation(type = BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Task_1", name = "Toast bread fixed")),
                reason = "fix missing label",
            )
        )
        val definition = validDefinition()

        val result = agent.validateAndRefineBpmn(BpmnRequest("Make toast"), graph(definition), converter.render(definition), context)

        assertEquals(1, context.llmInvocations.size)
        assertEquals(1, result.repairAttempts)
        val patchPrompt = context.llmInvocations.single().messages.joinToString("\n") { it.content }
        assertTrue(patchPrompt.contains("targeted name or label patches"), "Expected patch prompt but got: $patchPrompt")
    }

    @Test
    fun `no-op patch fails refinement fast`() {
        val patchableLintIssue = LintIssue(id = "Task_1", rule = "label-required", message = "Task label required")
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService = RecordingLintService(listOf(listOf(patchableLintIssue)))
        val converter = RecordingConverter()
        val patchApplier = object : BpmnPatchApplier() {
            override fun apply(definition: BpmnDefinition, patch: BpmnRepairPatch): PatchApplicationResult =
                PatchApplicationResult.NoOp
        }
        val agent = buildRepairAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter, patchApplier = patchApplier)
        val context = FakeActionContext()
        context.expectResponse(
            BpmnRepairPatch(operations = listOf(BpmnPatchOperation(type = BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Task_1", name = "Toast bread")))
        )
        val definition = validDefinition()

        val error = assertFailsWith<IllegalStateException> {
            agent.validateAndRefineBpmn(BpmnRequest("Make toast"), graph(definition), converter.render(definition), context)
        }
        assertTrue(error.message!!.contains("no-op"))
    }

    @Test
    fun `invalid patch falls back to full definition correction`() {
        val patchableLintIssue = LintIssue(id = "Task_1", rule = "label-required", message = "Task label required")
        val corrected = validDefinition(processName = "Make toast correctly")
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintService = RecordingLintService(listOf(listOf(patchableLintIssue), emptyList()))
        val converter = RecordingConverter()
        val patchApplier = object : BpmnPatchApplier() {
            override fun apply(definition: BpmnDefinition, patch: BpmnRepairPatch): PatchApplicationResult =
                PatchApplicationResult.Failure("unknown nodeId 'Task_X'")
        }
        val agent = buildRepairAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter, patchApplier = patchApplier)
        val context = FakeActionContext()
        context.expectResponse(
            BpmnRepairPatch(operations = listOf(BpmnPatchOperation(type = BpmnPatchOperationType.SET_NODE_NAME, nodeId = "Task_X", name = "fix")))
        )
        context.expectResponse(corrected)
        val definition = validDefinition()

        val result = agent.validateAndRefineBpmn(BpmnRequest("Make toast"), graph(definition), converter.render(definition), context)
        assertTrue(result.xml.contains("Make toast correctly"))
        assertEquals(2, context.llmInvocations.size)
    }

    @Test
    fun `validate and refine action fires only once`() {
        val action = BpmnRepairAgent::class.java.methods.single {
            it.name == "validateAndRefineBpmn"
        }.getAnnotation(com.embabel.agent.api.annotation.Action::class.java)

        assertEquals(ActionRetryPolicy.FIRE_ONCE, action.actionRetryPolicy)
    }

    private object NoOpEventPublisher : ApplicationEventPublisher {
        override fun publishEvent(event: Any) = Unit
    }

    private class RecordingLintService(
        private val responses: List<List<LintIssue>?>,
        private val docs: Map<String, String> = emptyMap(),
        private val autoFixResponses: List<BpmnAutoFixResult?> = emptyList(),
    ) : BpmnLintService() {
        val xmls = mutableListOf<String>()
        val phases = mutableListOf<BpmnLintPhase>()
        private var index = 0

        override fun lint(bpmnXml: String, phase: BpmnLintPhase): List<LintIssue>? {
            xmls += bpmnXml
            phases += phase
            return responses[index++]
        }

        override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> =
            buildMap {
                ruleNames.distinct().forEach { ruleName ->
                    docs[ruleName]?.let { put(ruleName, it) }
                }
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

    private class RecordingConverter : BpmnDefinitionToXmlConverter() {
        var renderCalls = 0

        override fun render(definition: BpmnDefinition): RenderedBpmn {
            renderCalls += 1
            return super.render(definition)
        }
    }

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

    private fun validDefinition(
        processId: String = "Process_MakeToast",
        processName: String = "Make toast",
    ) = BpmnDefinition(
        processId = processId,
        processName = processName,
        nodes = listOf(
            BpmnNode("StartEvent_1", "Order received", NodeType.START_EVENT, BpmnBounds(80.0, 120.0, 36.0, 36.0)),
            BpmnNode("Task_1", "Toast bread", NodeType.SERVICE_TASK, BpmnBounds(180.0, 98.0, 100.0, 80.0)),
            BpmnNode("EndEvent_1", "Toast served", NodeType.END_EVENT, BpmnBounds(320.0, 120.0, 36.0, 36.0)),
        ),
        sequences = listOf(
            BpmnEdge(
                "Flow_1",
                "StartEvent_1",
                "Task_1",
                waypoints = listOf(BpmnWaypoint(116.0, 138.0), BpmnWaypoint(180.0, 138.0)),
            ),
            BpmnEdge(
                "Flow_2",
                "Task_1",
                "EndEvent_1",
                waypoints = listOf(BpmnWaypoint(280.0, 138.0), BpmnWaypoint(320.0, 138.0)),
            ),
        ),
    )
}
