/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Budget
import com.embabel.agent.core.Goal
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.hitl.FormBindingRequest
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.common.Constants
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.authoring.BpmnGenerationStatus
import dev.groknull.bpmner.authoring.BpmnRequestDraft
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.authoring.internal.adapter.outbound.AgentPlatformBpmnAgentInvoker
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnDefinition
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnNode
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnNodeKind
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.GenerationMode
import dev.groknull.bpmner.conformance.BpmnLintingPort
import dev.groknull.bpmner.conformance.LintIssue
import dev.groknull.bpmner.conformance.internal.adapter.outbound.BpmnXsdValidator
import dev.groknull.bpmner.contract.FlatContractTestFixtures
import dev.groknull.bpmner.readiness.BpmnClarificationAnswers
import dev.groknull.bpmner.readiness.ClarificationQuestion
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.xmlunit.assertj.XmlAssert
import org.xmlunit.assertj.XmlAssert.assertThat
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Full end-to-end system test: validates the complete Embabel agent pipeline from BpmnRequest
 * through generation, repair, and layout to a written BPMN file. Per-module behavior is covered
 * by RepairModuleTest, GenerationModuleTest, and ValidationModuleTest.
 */
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
        "embabel.agent.platform.models.deepseek.api-key=test-key",
    ],
)
@SpringBootTest
class BpmnAgentFlowSystemTest : EmbabelMockitoIntegrationTest() {
    @MockitoBean
    private lateinit var bpmnXsdValidator: BpmnXsdValidator

    @MockitoBean
    private lateinit var bpmnLintingPort: BpmnLintingPort

    @Autowired
    private lateinit var bpmnAgentInvoker: AgentPlatformBpmnAgentInvoker

    @Test
    fun `interactive clarification loop asks only the first question and maps answer correctly`() {
        val request =
            BpmnRequest(
                processDescription = "When an order is submitted, the clerk reviews it.",
                mode = GenerationMode.INTERACTIVE,
            )

        val multipleQuestions = ProcessInputAssessment(
            verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
            overallScore = 40,
            dimensions = emptyList(),
            evidence = emptyList(),
            clarificationQuestions = listOf(
                ClarificationQuestion(id = "q1", questionText = "What is the first question?"),
                ClarificationQuestion(id = "q2", questionText = "What is the second question?"),
            ),
            rationale = "Needs clarification",
        )

        val readyAssessment = ProcessInputAssessment(
            verdict = ReadinessVerdict.READY,
            overallScore = 85,
            dimensions = emptyList(),
            evidence = emptyList(),
            rationale = "Ready",
        )

        whenCreateObject(
            { it.contains("Assess whether the source text describes a workflow that is ready for BPMN modelling") },
            ProcessInputAssessment::class.java,
        ).thenReturn(readyAssessment)

        val process = runGateProcess(ReadyBpmnContext::class.java, ephemeral = false, request, multipleQuestions)
        assertEquals(AgentProcessStatusCode.WAITING, process.status)

        @Suppress("UNCHECKED_CAST")
        val form = process.last(FormBindingRequest::class.java)
            as FormBindingRequest<BpmnClarificationAnswers>

        form.bind(BpmnClarificationAnswers("Finally the order is completed."), process)
        process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, process.status)

        // Verify the clarification history only mapped the first question
        val finalContext = process.last(ReadyBpmnContext::class.java)!!
        val history = finalContext.request.clarificationHistory
        assertEquals(1, history.size, "Only one question should be added to history")
        assertEquals("q1", history[0].questionId)
        assertEquals("What is the first question?", history[0].questionText)
        assertEquals("Finally the order is completed.", history[0].answerText)
    }

    @Test
    @Suppress("LongMethod")
    fun `planner resolves request through definition render validation and write`(
        @TempDir tempDir: Path,
    ) {
        val flatDefinition = validFlatDefinition()
        val outputFile = tempDir.resolve("process.bpmn")

        val assessment =
            ProcessInputAssessment(
                verdict = ReadinessVerdict.READY,
                overallScore = 100,
                dimensions = emptyList(),
                evidence = emptyList(),
                rationale = "Ready",
            )

        // 1. Mock Contract — use the contract module's published test fixture to avoid
        //    reaching into contract.internal.adapter.inbound (S5 — ARCHITECTURE §5 S5, §1.5).
        whenCreateObject(
            { it.contains("Extract a source-grounded process contract") },
            FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS,
        ).thenReturn(FlatContractTestFixtures.makeToastContract())

        // 2. Mock Generation
        whenCreateObject(
            { it.contains("Generate a BPMN definition object from the validated process contract") },
            FlatBpmnDefinition::class.java,
        ).thenReturn(flatDefinition)

        // 3. Mock Alignment
        whenCreateObject(
            { it.contains("You are a BPMN alignment validator") },
            AlignmentFindings::class.java,
        ).thenReturn(
            AlignmentFindings(
                issues = emptyList(),
                rationale = "Aligned",
            ),
        )

        // 4. Mock Validators
        `when`(bpmnXsdValidator.validateDetailed(anyString()))
            .thenReturn(emptyList())
        doReturn(emptyList<LintIssue>())
            .`when`(bpmnLintingPort)
            .lint(anyDefinition())

        val result =
            bpmnAgentInvoker.generate(
                BpmnRequest(
                    processDescription = "Make toast",
                    outputFile = outputFile.toString(),
                ),
                assessment,
            )

        assertEquals(outputFile.toString(), result.outputFile)
        assertXml(result.xml!!).nodesByXPath("/bpmn:definitions/bpmn:process").exist()
        assertEquals(result.xml, outputFile.readText())
        verify(bpmnXsdValidator, times(2)).validateDetailed(anyString())
        // BpmnLayoutAgent.autoFixBpmnXml stopped calling lint() in #243 (passthrough);
        // only BpmnEvaluationPipeline still invokes the rule engine once during evaluation.
        verify(bpmnLintingPort, times(1)).lint(anyDefinition())
    }

    @Test
    @Suppress("LongMethod")
    fun `shell user input plans through drafting readiness generation and write`(
        @TempDir tempDir: Path,
    ) {
        // Test-only: prose that clears every deterministic readiness gate (START_TRIGGER
        // "When"/"submitted", END_STATE "completed", >=2 process verbs, SEQUENCE "then"/"finally"), so
        // the post-check floor doesn't contradict the mocked-READY verdict. A READY shell request
        // approves directly and never reaches the clarification WaitFor (which an ephemeral=true
        // transform cannot service).
        val prose =
            "When an order is submitted, the clerk reviews it, then approves it, then ships it, " +
                "and finally the order is completed."
        val outputFile = tempDir.resolve("order.bpmn")
        val flatDefinition = validFlatDefinition()
        val assessment =
            ProcessInputAssessment(
                verdict = ReadinessVerdict.READY,
                overallScore = 100,
                dimensions = emptyList(),
                evidence = emptyList(),
                rationale = "Ready",
            )

        whenCreateObject(
            { it.contains("Extract a BPMN generation request from the user's shell instruction") },
            BpmnRequestDraft::class.java,
        ).thenReturn(
            BpmnRequestDraft(
                processDescription = prose,
                outputFile = outputFile.toString(),
            ),
        )
        whenCreateObject(
            { it.contains("Assess whether the source text describes a workflow that is ready for BPMN modelling") },
            ProcessInputAssessment::class.java,
        ).thenReturn(assessment)

        whenCreateObject(
            { it.contains("Extract a source-grounded process contract") },
            FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS,
        ).thenReturn(FlatContractTestFixtures.makeToastContract())

        whenCreateObject(
            { it.contains("Generate a BPMN definition object from the validated process contract") },
            FlatBpmnDefinition::class.java,
        ).thenReturn(flatDefinition)
        whenCreateObject(
            { it.contains("You are a BPMN alignment validator") },
            AlignmentFindings::class.java,
        ).thenReturn(AlignmentFindings(issues = emptyList(), rationale = "Aligned"))

        `when`(bpmnXsdValidator.validateDetailed(anyString()))
            .thenReturn(emptyList())
        doReturn(emptyList<LintIssue>())
            .`when`(bpmnLintingPort)
            .lint(anyDefinition())

        val result =
            AgentPlatformTypedOps(agentPlatform)
                .transform(
                    UserInput("Generate BPMN for the order process and write it to $outputFile."),
                    BpmnResult::class.java,
                    ProcessOptions(budget = Budget(actions = 100), ephemeral = true),
                )

        assertEquals(BpmnGenerationStatus.GENERATED, result.status)
        assertEquals(outputFile.toString(), result.outputFile)
        assertXml(result.xml!!).nodesByXPath("/bpmn:definitions/bpmn:process").exist()
        assertEquals(result.xml, outputFile.readText())
    }

    @Test
    fun `single-shot needs-clarification returns a needs-clarification result without waiting`() {
        // SINGLE_SHOT cannot service a WaitFor, so a not-ready verdict must terminate immediately.
        val result =
            bpmnAgentInvoker.generate(
                BpmnRequest(processDescription = "Make toast", mode = GenerationMode.SINGLE_SHOT),
                needsClarification(),
            )

        assertEquals(BpmnGenerationStatus.NEEDS_CLARIFICATION, result.status)
        assertEquals(ReadinessVerdict.NEEDS_CLARIFICATION, result.readinessReport?.verdict)
    }

    @Test
    fun `interactive needs-clarification pauses for clarification input`() {
        val request = BpmnRequest(processDescription = "Ship something", mode = GenerationMode.INTERACTIVE)

        val process = runGateProcess(ReadyBpmnContext::class.java, ephemeral = false, request, needsClarification())

        assertEquals(AgentProcessStatusCode.WAITING, process.status)
    }

    @Test
    fun `interactive clarification loop converges to ready after an answer`() {
        // Re-assessment after the answer runs the readiness agent's LLM + deterministic post-check.
        whenCreateObject(
            { it.contains("Assess whether the source text describes a workflow that is ready for BPMN modelling") },
            ProcessInputAssessment::class.java,
        ).thenReturn(
            ProcessInputAssessment(
                verdict = ReadinessVerdict.READY,
                overallScore = 85,
                dimensions = emptyList(),
                evidence = emptyList(),
                rationale = "Ready",
            ),
        )
        // The description lacks an END_STATE marker; the answer supplies it, and the post-check (which
        // now scores clarification answers too) clears, so the loop reaches READY.
        val request =
            BpmnRequest(
                processDescription = "When an order is submitted, the clerk reviews it, then approves it.",
                mode = GenerationMode.INTERACTIVE,
            )

        val process = runGateProcess(ReadyBpmnContext::class.java, ephemeral = false, request, needsClarification())
        assertEquals(AgentProcessStatusCode.WAITING, process.status)

        @Suppress("UNCHECKED_CAST")
        val form = process.last(FormBindingRequest::class.java) as FormBindingRequest<BpmnClarificationAnswers>
        form.bind(BpmnClarificationAnswers("Finally the order is completed."), process)
        process.run()

        assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
        assertEquals(ReadinessVerdict.READY, process.last(ReadyBpmnContext::class.java)!!.assessment.verdict)
    }

    private fun runGateProcess(
        resultClass: Class<*>,
        ephemeral: Boolean,
        vararg seeds: Any,
    ): AgentProcess {
        val goalAgent =
            agentPlatform
                .createAgent(
                    name = "goal-${resultClass.simpleName}",
                    provider = Constants.EMBABEL_PROVIDER,
                    description = "Goal agent for ${resultClass.simpleName}",
                ).withSingleGoal(
                    Goal(
                        name = "create-${resultClass.simpleName}",
                        description = "Create ${resultClass.simpleName}",
                        satisfiedBy = resultClass,
                    ),
                )
        return agentPlatform
            .createAgentProcessFrom(
                goalAgent,
                ProcessOptions(budget = Budget(actions = 100), ephemeral = ephemeral),
                *seeds,
            ).run()
    }

    private companion object {
        fun needsClarification() = ProcessInputAssessment(
            verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
            overallScore = 40,
            dimensions = emptyList(),
            evidence = emptyList(),
            clarificationQuestions =
            listOf(ClarificationQuestion(id = "q-end", questionText = "What final state should the process reach?")),
            rationale = "Needs clarification",
        )

        fun validFlatDefinition() = FlatBpmnDefinition(
            processId = "Process_MakeToast",
            processName = "Make Toast",
            nodes = listOf(
                FlatBpmnNode(id = "start", type = FlatBpmnNodeKind.START_EVENT, name = "Start"),
                FlatBpmnNode(id = "task1", type = FlatBpmnNodeKind.USER_TASK, name = "Toast bread"),
                FlatBpmnNode(id = "end", type = FlatBpmnNodeKind.END_EVENT, name = "End"),
            ),
            sequences = listOf(
                BpmnEdge(id = "f1", sourceRef = "start", targetRef = "task1"),
                BpmnEdge(id = "f2", sourceRef = "task1", targetRef = "end"),
            ),
        )

        fun anyDefinition(): dev.groknull.bpmner.bpmn.BpmnDefinition = anyNonNull()

        fun <T> anyNonNull(): T {
            any<T>()
            @Suppress("UNCHECKED_CAST")
            return null as T
        }

        private val NAMESPACES = mapOf(
            "bpmn" to "http://www.omg.org/spec/BPMN/20100524/MODEL",
        )

        private fun assertXml(xml: String): XmlAssert = assertThat(xml).withNamespaceContext(NAMESPACES)
    }
}
