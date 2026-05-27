/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

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
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairSafety
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.generation.BpmnContractFidelityChecker
import dev.groknull.bpmner.generation.BpmnDefinitionToXmlConverter
import dev.groknull.bpmner.generation.DefaultFlowAssigner
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnPatchApplier
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnRepairPromptFactory
import dev.groknull.bpmner.repair.internal.domain.BpmnAttemptRecordFactory
import dev.groknull.bpmner.repair.internal.domain.handlers.BypassGatewayHandler
import dev.groknull.bpmner.repair.internal.domain.handlers.ConvergingGatewayClearNameHandler
import dev.groknull.bpmner.repair.internal.domain.handlers.InsertConvergingGatewayHandler
import dev.groknull.bpmner.repair.internal.domain.handlers.SplitJoinForkGatewayHandler
import dev.groknull.bpmner.rules.RuleRegistry
import dev.groknull.bpmner.validation.BpmnAutoFixResult
import dev.groknull.bpmner.validation.BpmnDefinitionValidator
import dev.groknull.bpmner.validation.BpmnDiagnosticNormalizer
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnEvaluationPipeline
import dev.groknull.bpmner.validation.BpmnFingerprintService
import dev.groknull.bpmner.validation.BpmnLintRuleCapability
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnRuleGuidancePort
import dev.groknull.bpmner.validation.BpmnXsdValidator
import dev.groknull.bpmner.validation.LintIssue
import dev.groknull.bpmner.validation.XsdValidationIssue
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.annotation.AnnotationAwareOrderComparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("TooManyFunctions") // test class — each @Test method is one function
class BpmnRefinementEngineTest {
    @Test
    fun `strategy annotations order deterministic repairs before LLM repairs`() {
        val config = BpmnConfig()
        val lint = RecordingLintingPort(listOf(emptyList()))
        val xsd = RecordingXsdValidator(listOf(emptyList()))
        val fingerprints = BpmnFingerprintService()
        val prompts = BpmnRepairPromptFactory(lint, fingerprints, NoopRuleGuidancePort)
        val patchApplier = BpmnPatchApplier()
        val strategies =
            mutableListOf<BpmnRepairStrategy>(
                FullLlmRewriteRepairStrategy(config, prompts),
                LlmPatchRepairStrategy(config, prompts, patchApplier),
                TargetedLabelRepairStrategy(config, prompts, patchApplier),
                DeterministicTopologyRepairStrategy(
                    BpmnLocalModelFixHandlerRegistry(emptyList()),
                    patchApplier,
                    EmptyRuleRegistry,
                ),
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

    // Two tests previously exercised the bpmnlint `LOCAL_XML_FIX` auto-fix path and its
    // `local-fix-failed` LLM-fallback annotation. After #243 collapsed `LOCAL_XML_FIX` into
    // `LOCAL_MODEL_FIX`, both pathways no longer exist: Kotlin handlers either patch the
    // model in-process or fall through silently. The LOCAL_MODEL_FIX integration is covered
    // by `local model fix strategy repairs topology diagnostic without calling LLM` below
    // and by `DeterministicTopologyRepairStrategyTest` at the unit level.

    @Test
    fun `local model fix strategy repairs topology diagnostic without calling LLM`() {
        val invalid = joinForkDefinition()
        val xsd = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val topologyCapability =
            BpmnLintRuleCapability(
                id = "no-gateway-join-fork",
                kind = RepairKind.LOCAL_MODEL_FIX,
                repairSafety = RepairSafety.SAFE_AUTOMATIC,
                fixHandler = "splitJoinForkGateway",
                handlerExists = true,
                replacementMap = null,
            )
        val lint =
            RecordingLintingPort(
                lintResponses =
                listOf(
                    listOf(
                        LintIssue(
                            id = "Gateway_1",
                            rule = "bpmner/no-gateway-join-fork",
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

        val result =
            engine.refine(
                BpmnRequest("Route an order"),
                testLaidOutGraph(invalid, withOwnership = true),
                converter.render(invalid),
                joinForkContract(),
                context,
            )

        assertEquals(1, result.repairAttempts)
        assertTrue(context.llmInvocations.isEmpty())
        assertEquals(2, xsd.xmls.size)
        assertEquals(2, lint.definitions.size)
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
        val lint = RecordingLintingPort(listOf(emptyList()))
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
                graph = testLaidOutGraph(invalid, withOwnership = true),
                definition = invalid,
                rendered = null,
                repairAttempts = 0,
            )

        assertTrue(evaluation.diagnostics.any { it.source == BpmnDiagnosticSource.GRAPH })
        assertEquals(0, xsd.xmls.size)
        assertEquals(0, lint.definitions.size)
    }

    private fun refinementEngine(
        config: BpmnConfig,
        lintingPort: BpmnLintingPort,
        xsdValidator: BpmnXsdValidator,
        converter: BpmnDefinitionToXmlConverter,
    ): BpmnRefinementEngine {
        val fingerprints = BpmnFingerprintService()
        val normalizer = BpmnDiagnosticNormalizer(lintingPort)
        val promptFactory = BpmnRepairPromptFactory(lintingPort, fingerprints, NoopRuleGuidancePort)
        val patchApplier = BpmnPatchApplier()
        return BpmnRefinementEngine(
            config = config,
            bpmnRenderer = converter,
            validator =
            BpmnEvaluationPipeline(
                config = config,
                bpmnLintingPort = lintingPort,
                bpmnXsdValidationPort = xsdValidator,
                bpmnDefinitionValidator = BpmnDefinitionValidator(),
                normalizer = normalizer,
                fingerprints = fingerprints,
            ),
            attemptRecordFactory = BpmnAttemptRecordFactory(fingerprints),
            promptFactory = promptFactory,
            fingerprints = fingerprints,
            strategies =
            listOf(
                DeterministicTopologyRepairStrategy(
                    BpmnLocalModelFixHandlerRegistry(
                        listOf(
                            SplitJoinForkGatewayHandler(),
                            InsertConvergingGatewayHandler(),
                            BypassGatewayHandler(),
                            ConvergingGatewayClearNameHandler(),
                        ),
                    ),
                    patchApplier,
                    EmptyRuleRegistry,
                ),
                TargetedLabelRepairStrategy(config, promptFactory, patchApplier),
                LlmPatchRepairStrategy(config, promptFactory, patchApplier),
                FullLlmRewriteRepairStrategy(config, promptFactory),
            ),
            eventPublisher = NoOpEventPublisher,
            defaultFlowAssigner = DefaultFlowAssigner(),
            fidelityChecker = BpmnContractFidelityChecker(),
        )
    }

    private object NoOpEventPublisher : ApplicationEventPublisher {
        override fun publishEvent(event: Any) = Unit
    }

    private class RecordingLintingPort(
        lintResponses: List<List<LintIssue>?>,
        private val autoFixResponse: BpmnAutoFixResult? = null,
        private val capabilities: Map<String, BpmnLintRuleCapability> = emptyMap(),
    ) : BpmnLintingPort {
        val definitions = mutableListOf<BpmnDefinition>()
        private var _autoFixCalls = 0
        val autoFixCalls: Int get() = _autoFixCalls
        private val responses = lintResponses
        private var index = 0

        override fun lint(definition: BpmnDefinition): List<LintIssue>? {
            definitions += definition
            return responses[index++]
        }

        override fun autoFix(
            bpmnXml: String,
            issues: List<LintIssue>,
        ): BpmnAutoFixResult? {
            _autoFixCalls++
            return autoFixResponse
        }

        override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> = emptyMap()

        override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> = capabilities
    }

    private object EmptyRuleRegistry : RuleRegistry {
        override fun activeRules(): List<BpmnRule> = emptyList()
        override fun ruleById(id: String): BpmnRule? = null
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
        ): PromptRunner = delegate.promptRunner(
            llm = llm,
            toolGroups = toolGroups,
            toolObjects = toolObjects,
            promptContributors = promptContributors,
            contextualPromptContributors = contextualPromptContributors,
            generateExamples = generateExamples,
        )
    }

    private fun validDefinition(): BpmnDefinition = testBpmnDefinition(
        processId = "Process_Test",
        processName = "Test",
        task = BpmnUserTask("Task_1", "Do work"),
        startName = "Started",
        endName = "Done",
    )

    private fun joinForkContract(): ProcessContract = ProcessContract(
        id = "c-jf",
        processName = "Join fork",
        summary = "Join-fork test",
        trigger = "start",
        activities =
        listOf(
            ContractActivity.Service(id = "Task_1", name = "Do one"),
            ContractActivity.Service(id = "Task_2", name = "Do two"),
        ),
        endStates = listOf(ContractEndState(id = "EndEvent_1", name = "Done")),
    )

    private fun joinForkDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "Process_JoinFork",
        processName = "Join fork",
        nodes =
        listOf(
            BpmnStartEvent("StartEvent_1", "A"),
            BpmnStartEvent("StartEvent_2", "B"),
            BpmnExclusiveGateway("Gateway_1", "Route?"),
            BpmnServiceTask("Task_1", "Do one"),
            BpmnServiceTask("Task_2", "Do two"),
            BpmnEndEvent("EndEvent_1", "Done"),
        ),
        sequences =
        listOf(
            BpmnEdge(
                "Flow_1",
                "StartEvent_1",
                "Gateway_1",
            ),
            BpmnEdge(
                "Flow_2",
                "StartEvent_2",
                "Gateway_1",
            ),
            BpmnEdge(
                "Flow_3",
                "Gateway_1",
                "Task_1",
            ),
            BpmnEdge(
                "Flow_4",
                "Gateway_1",
                "Task_2",
            ),
            BpmnEdge(
                "Flow_5",
                "Task_1",
                "EndEvent_1",
            ),
            BpmnEdge(
                "Flow_6",
                "Task_2",
                "EndEvent_1",
            ),
        ),
    )

    private object NoopRuleGuidancePort : BpmnRuleGuidancePort {
        override fun getLlmRuleGuidance(): String = ""
    }
}
