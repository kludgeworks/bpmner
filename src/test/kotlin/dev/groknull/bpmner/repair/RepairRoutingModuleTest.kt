package dev.groknull.bpmner.repair
import dev.groknull.bpmner.core.BpmnRequest

import dev.groknull.bpmner.core.BpmnDefinition


import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.modulith.test.PublishedEvents
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * End-to-end tests for repair routing observability (#59):
 *  - locally fixable rule repairs with no LLM invocation
 *  - LLM-only rule routes straight to LLM, local strategy is a no-op
 *  - mixed local+LLM applies the local fix first and the LLM prompt sees only the unresolved diagnostic
 *  - per-attempt route-summary log records the routing decision
 */
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES, verifyAutomatically = false)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
    ],
)
@MockitoBean(types = [AgentPlatformBpmnAgentInvoker::class])
class RepairRoutingModuleTest {
    @MockitoBean
    private lateinit var bpmnLintService: BpmnLintService

    @MockitoBean
    private lateinit var bpmnXsdValidator: BpmnXsdValidator

    @Autowired
    private lateinit var refinementEngine: BpmnRefinementEngine

    private lateinit var engineLogAppender: ListAppender<ILoggingEvent>
    private lateinit var engineLogger: Logger

    @BeforeEach
    fun attachLogAppender() {
        engineLogger = LoggerFactory.getLogger(BpmnRefinementEngine::class.java) as Logger
        engineLogAppender = ListAppender<ILoggingEvent>().apply { start() }
        engineLogger.addAppender(engineLogAppender)
    }

    @AfterEach
    fun detachLogAppender() {
        engineLogger.detachAppender(engineLogAppender)
        engineLogAppender.stop()
    }

    @Test
    fun `locally fixable lint violation is repaired without any LLM invocation`(events: PublishedEvents) {
        val localCapability =
            BpmnLintRuleCapability(
                id = "name-01",
                kind = RepairKind.LOCAL_XML_FIX,
                repairSafety = BpmnRepairSafety.SAFE_AUTOMATIC,
                fixHandler = "stripTypeWords",
                handlerExists = true,
                replacementMap = null,
            )
        val lintIssue =
            LintIssue(
                id = "Task_1",
                rule = "bpmner/name-01",
                message = "Element name must not include its BPMN element type",
            )
        `when`(bpmnXsdValidator.validateDetailed(anyString())).thenReturn(emptyList())
        `when`(bpmnLintService.lintRuleCapabilities()).thenReturn(mapOf("name-01" to localCapability))
        // attempt 1: one local-fix issue → attempt 2 after auto-fix: clean
        `when`(bpmnLintService.lint(anyString(), anyPhase()))
            .thenReturn(listOf(lintIssue))
            .thenReturn(emptyList())
        `when`(bpmnLintService.autoFix(anyString(), anyLintIssues(), anyPhase()))
            .thenReturn(
                BpmnAutoFixResult(
                    changed = true,
                    xml = renderedXmlOf(testBpmnDefinition(processName = "Locally fixed")),
                    applied = listOf(BpmnAutoFixChange("bpmner/name-01", "Task_1", "stripped")),
                ),
            )
        `when`(bpmnLintService.ruleDocs(anyRuleNames())).thenReturn(emptyMap())

        val context = FakeActionContext()
        val definition = testBpmnDefinition()
        val graph = testLaidOutGraph(definition, withOwnership = true)
        val rendered = BpmnDefinitionToXmlConverter().render(graph)

        refinementEngine.refine(
            request = BpmnRequest(processDescription = "Make toast"),
            graph = graph,
            rendered = rendered,
            context = context,
        )

        assertTrue(context.llmInvocations.isEmpty(), "no LLM call expected for a LOCAL_XML_FIX rule")
        assertTrue(
            events.ofType(BpmnValidationPassedEvent::class.java).toList().isNotEmpty(),
            "expected a BpmnValidationPassedEvent after local-only repair",
        )
        assertRouteSummaryLogged(
            attemptNumber = 1,
            "localAttempted=1",
            "localApplied=1",
            "localFailed=0",
            "llmRouted=0",
        )
    }

    @Test
    fun `LLM-only rule routes directly to LLM and bypasses local auto-fix`() {
        val llmCapability =
            BpmnLintRuleCapability(
                id = "name-clarity",
                kind = RepairKind.LLM_MODEL_PATCH,
                repairSafety = BpmnRepairSafety.LLM_ONLY,
                fixHandler = null,
                handlerExists = false,
                replacementMap = null,
            )
        val llmIssue =
            LintIssue(
                id = "EndEvent_1",
                rule = "bpmner/name-clarity",
                message = "End event name should describe a business outcome",
            )
        `when`(bpmnXsdValidator.validateDetailed(anyString())).thenReturn(emptyList())
        `when`(bpmnLintService.lintRuleCapabilities()).thenReturn(mapOf("name-clarity" to llmCapability))
        `when`(bpmnLintService.lint(anyString(), anyPhase()))
            .thenReturn(listOf(llmIssue))
            .thenReturn(emptyList())
        `when`(bpmnLintService.ruleDocs(anyRuleNames())).thenReturn(emptyMap())

        val context =
            FakeActionContext().also {
                it.expectResponse(setNodeNamePatch("EndEvent_1", "Toast served to customer"))
            }
        val definition = testBpmnDefinition()
        val graph = testLaidOutGraph(definition, withOwnership = true)
        val rendered = BpmnDefinitionToXmlConverter().render(graph)

        refinementEngine.refine(
            request = BpmnRequest(processDescription = "Make toast"),
            graph = graph,
            rendered = rendered,
            context = context,
        )

        assertEquals(1, context.llmInvocations.size, "exactly one LLM call expected for an LLM_MODEL_PATCH rule")
        verify(bpmnLintService, never()).autoFix(anyString(), anyLintIssues(), anyPhase())
        assertRouteSummaryLogged(
            attemptNumber = 1,
            "localAttempted=0",
            "localApplied=0",
            "localFailed=0",
            "llmRouted=1",
        )
    }

    @Test
    fun `mixed local and LLM diagnostics route local first then LLM with only unresolved diagnostic`() {
        val localCapability =
            BpmnLintRuleCapability(
                id = "name-01",
                kind = RepairKind.LOCAL_XML_FIX,
                repairSafety = BpmnRepairSafety.SAFE_AUTOMATIC,
                fixHandler = "stripTypeWords",
                handlerExists = true,
                replacementMap = null,
            )
        val llmCapability =
            BpmnLintRuleCapability(
                id = "name-clarity",
                kind = RepairKind.LLM_MODEL_PATCH,
                repairSafety = BpmnRepairSafety.LLM_ONLY,
                fixHandler = null,
                handlerExists = false,
                replacementMap = null,
            )
        val localIssue =
            LintIssue(
                id = "Task_1",
                rule = "bpmner/name-01",
                message = "Element name must not include its BPMN element type",
            )
        val llmIssue =
            LintIssue(
                id = "EndEvent_1",
                rule = "bpmner/name-clarity",
                message = "End event name should describe a business outcome",
            )
        `when`(bpmnXsdValidator.validateDetailed(anyString())).thenReturn(emptyList())
        `when`(bpmnLintService.lintRuleCapabilities()).thenReturn(
            mapOf(
                "name-01" to localCapability,
                "name-clarity" to llmCapability,
            ),
        )
        // attempt 1: both → after local fix attempt 2: only LLM → after LLM rewrite: clean
        `when`(bpmnLintService.lint(anyString(), anyPhase()))
            .thenReturn(listOf(localIssue, llmIssue))
            .thenReturn(listOf(llmIssue))
            .thenReturn(emptyList())
        val locallyFixed = testBpmnDefinition(processName = "Locally fixed")
        `when`(bpmnLintService.autoFix(anyString(), anyLintIssues(), anyPhase()))
            .thenReturn(
                BpmnAutoFixResult(
                    changed = true,
                    xml = renderedXmlOf(locallyFixed),
                    applied = listOf(BpmnAutoFixChange("bpmner/name-01", "Task_1", "stripped")),
                ),
            )
        `when`(bpmnLintService.ruleDocs(anyRuleNames())).thenReturn(emptyMap())

        val context =
            FakeActionContext().also {
                it.expectResponse(setNodeNamePatch("EndEvent_1", "Toast served to customer"))
            }
        val definition = testBpmnDefinition()
        val graph = testLaidOutGraph(definition, withOwnership = true)
        val rendered = BpmnDefinitionToXmlConverter().render(graph)

        refinementEngine.refine(
            request = BpmnRequest(processDescription = "Make toast"),
            graph = graph,
            rendered = rendered,
            context = context,
        )

        assertEquals(1, context.llmInvocations.size, "expected exactly one LLM call after the local diagnostic was resolved")
        val prompt =
            context.llmInvocations
                .single()
                .messages
                .joinToString("\n") { it.content }
        assertTrue(
            prompt.contains("rule=bpmner/name-clarity"),
            "LLM prompt must reference the unresolved LLM rule; got: $prompt",
        )
        assertFalse(
            prompt.contains("rule=bpmner/name-01"),
            "LLM prompt must not include the locally-resolved rule; got: $prompt",
        )
        assertRouteSummaryLogged(
            attemptNumber = 1,
            "localAttempted=1",
            "localApplied=1",
            "localFailed=0",
            "llmRouted=1",
        )
        assertRouteSummaryLogged(
            attemptNumber = 2,
            "localAttempted=0",
            "llmRouted=1",
        )
    }

    @Test
    fun `local fix failure annotates LLM prompt and records local failure in route summary`() {
        val localCapability =
            BpmnLintRuleCapability(
                id = "name-01",
                kind = RepairKind.LOCAL_XML_FIX,
                repairSafety = BpmnRepairSafety.SAFE_AUTOMATIC,
                fixHandler = "stripTypeWords",
                handlerExists = true,
                replacementMap = null,
            )
        val localIssue =
            LintIssue(
                id = "Task_1",
                rule = "bpmner/name-01",
                message = "Element name must not include its BPMN element type",
            )
        `when`(bpmnXsdValidator.validateDetailed(anyString())).thenReturn(emptyList())
        `when`(bpmnLintService.lintRuleCapabilities()).thenReturn(mapOf("name-01" to localCapability))
        `when`(bpmnLintService.lint(anyString(), anyPhase()))
            .thenReturn(listOf(localIssue))
            .thenReturn(emptyList())
        `when`(bpmnLintService.autoFix(anyString(), anyLintIssues(), anyPhase()))
            .thenReturn(
                BpmnAutoFixResult(
                    changed = false,
                    xml = "",
                    errors = listOf(BpmnAutoFixError("bpmner/name-01", "Task_1", "handler boom")),
                ),
            )
        `when`(bpmnLintService.ruleDocs(anyRuleNames())).thenReturn(emptyMap())

        val context =
            FakeActionContext().also {
                it.expectResponse(setNodeNamePatch("Task_1", "Toast bread carefully"))
            }
        val definition = testBpmnDefinition()
        val graph = testLaidOutGraph(definition, withOwnership = true)
        val rendered = BpmnDefinitionToXmlConverter().render(graph)

        refinementEngine.refine(
            request = BpmnRequest(processDescription = "Make toast"),
            graph = graph,
            rendered = rendered,
            context = context,
        )

        assertEquals(1, context.llmInvocations.size, "LLM should be called after local failure")
        val prompt =
            context.llmInvocations
                .single()
                .messages
                .joinToString("\n") { it.content }
        assertTrue(prompt.contains("[local-fix-failed: handler boom]"), "LLM prompt should annotate the failure")
        assertRouteSummaryLogged(
            attemptNumber = 1,
            "localAttempted=1",
            "localApplied=0",
            "localFailed=1",
            "llmRouted=0",
        )
    }

    private fun setNodeNamePatch(
        nodeId: String,
        name: String,
    ): BpmnRepairPatch =
        BpmnRepairPatch(
            operations = listOf(BpmnPatchOperation(type = BpmnPatchOperationType.SET_NODE_NAME, nodeId = nodeId, name = name)),
        )

    private fun assertRouteSummaryLogged(
        attemptNumber: Int,
        vararg expectedFragments: String,
    ) {
        val match =
            engineLogAppender.list
                .filter { it.level == Level.INFO }
                .firstOrNull { event ->
                    val formatted = event.formattedMessage
                    formatted.contains("Repair attempt $attemptNumber route summary") &&
                        expectedFragments.all { formatted.contains(it) }
                }
        assertNotNull(
            match,
            "Expected a route-summary log for attempt $attemptNumber containing all of " +
                expectedFragments.joinToString(prefix = "[", postfix = "]") +
                "; saw: " + engineLogAppender.list.joinToString("\n") { it.formattedMessage },
        )
    }

    private fun renderedXmlOf(definition: BpmnDefinition): String =
        BpmnDefinitionToXmlConverter().render(testLaidOutGraph(definition, withOwnership = true)).xml

    private fun anyString(): String = ArgumentMatchers.anyString()

    private fun anyPhase(): BpmnLintPhase = ArgumentMatchers.any(BpmnLintPhase::class.java) ?: BpmnLintPhase.FINAL_POST_LAYOUT

    private fun anyLintIssues(): List<LintIssue> = ArgumentMatchers.anyList()

    private fun anyRuleNames(): Collection<String> = ArgumentMatchers.anyCollection()

    private class FakeActionContext(
        private val delegate: FakeOperationContext = FakeOperationContext(),
    ) : ActionContext,
        OperationContext by delegate {
        override val processContext = delegate.processContext
        override val action: Action? = null
        override val toolGroups: Set<ToolGroupRequirement> get() = delegate.toolGroups
        override val operation = delegate.operation

        val llmInvocations get() = delegate.llmInvocations

        fun expectResponse(response: Any) = delegate.expectResponse(response)

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
}
