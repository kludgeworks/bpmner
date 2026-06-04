/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Condition
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.hitl.WaitFor
import com.embabel.agent.domain.io.UserInput
import com.embabel.chat.UserMessage
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnRequestDraft
import dev.groknull.bpmner.core.BpmnRequestResolver
import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.SourceEvidence
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.readiness.BpmnClarificationAnswers
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.BpmnReadinessState
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessReportWriter
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import org.jmolecules.architecture.hexagonal.Application
import org.slf4j.LoggerFactory

@Application
@Agent(description = "Resolve shell BPMN requests and gate generation on readiness")
// Each @Action and @Condition must be a distinct method for the Embabel GOAP planner to dispatch it;
// the function count is structural, not incidental complexity.
@Suppress("TooManyFunctions")
internal class BpmnGenerationGateAgent(
    private val config: BpmnConfig,
    private val requestResolver: BpmnRequestResolver,
    private val readinessInvoker: BpmnReadinessInvoker,
    private val readinessReportWriter: ReadinessReportWriter,
) {
    private val logger = LoggerFactory.getLogger(BpmnGenerationGateAgent::class.java)

    @Action(description = "Extract a structured BPMN request draft from shell user input")
    fun draftBpmnRequest(
        userInput: UserInput,
        context: OperationContext,
    ): BpmnRequestDraft {
        val prompt =
            """
            Extract a BPMN generation request from the user's shell instruction.

            Rules:
            - Put the workflow prose in processDescription when the user described the workflow directly.
            - Put a file path in processFile only when the user explicitly says the workflow is in a file.
            - Put an output path in outputFile only when the user asks to write to a specific file.
            - Put inline style guidance in styleGuide, or a style-guide file path in styleGuideFile.
            - Do not invent files or output paths.

            User instruction:
            ${userInput.content}
            """.trimIndent()

        return config.readinessAssessor
            .promptRunner(context)
            .creating(BpmnRequestDraft::class.java)
            .fromMessages(listOf(UserMessage(prompt)))
    }

    @Action(description = "Resolve shell BPMN request draft into a generation request")
    fun resolveBpmnRequest(draft: BpmnRequestDraft): BpmnRequest = requestResolver.resolveShellRequest(draft)

    @Action(description = "Assess BPMN request readiness for generation")
    fun assessBpmnReadiness(request: BpmnRequest): BpmnReadinessState {
        val assessment = readinessInvoker.assess(request)
        logger.info(
            "Readiness assessment complete. verdict={}, overallScore={}, clarificationRound={}",
            assessment.verdict,
            assessment.overallScore,
            request.clarificationHistory.size,
        )
        return BpmnReadinessState(
            request = request,
            assessment = assessment,
            clarificationRound = request.clarificationHistory.size,
        )
    }

    @AchievesGoal(
        description = "Resolve and approve a BPMN generation request for downstream generation",
        export =
        Export(
            name = "prepareBpmnGeneration",
            remote = false,
            startingInputTypes = [UserInput::class, BpmnRequest::class],
        ),
    )
    @Action(description = "Approve a ready BPMN request for contract extraction", pre = ["bpmnRequestReady"])
    fun approveReadyBpmnRequest(state: BpmnReadinessState): ReadyBpmnContext = ReadyBpmnContext(
        request = state.request,
        assessment = state.assessment,
    )

    @Action(description = "Accept externally assessed ready BPMN request for generation", pre = ["assessmentReady"])
    fun approveExternallyAssessedBpmnRequest(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): ReadyBpmnContext = ReadyBpmnContext(request = request, assessment = assessment)

    @Action(
        description = "Ask the user for BPMN readiness clarifications",
        pre = ["bpmnClarificationAvailable"],
        canRerun = true,
        trigger = BpmnReadinessState::class,
    )
    fun askForClarification(state: BpmnReadinessState): BpmnClarificationAnswers = WaitFor.formSubmission(
        clarificationPrompt(state),
        BpmnClarificationAnswers::class.java,
    )

    @Action(
        description = "Apply BPMN clarification answers and reassess readiness",
        canRerun = true,
        trigger = BpmnClarificationAnswers::class,
    )
    fun applyClarificationAnswers(
        state: BpmnReadinessState,
        answers: BpmnClarificationAnswers,
    ): BpmnReadinessState {
        val trimmedAnswers = answers.answers.trim()
        require(trimmedAnswers.isNotEmpty()) { "Clarification answers must not be blank." }
        // The shell form accepts one consolidated response; each pending question records that answer
        // so the next readiness pass can decide which gaps were actually resolved.
        val exchanges = state.assessment.clarificationQuestions.map { question ->
            ClarificationExchange(
                questionId = question.id,
                questionText = question.questionText,
                answerText = trimmedAnswers,
                relatedMissingAreas = question.relatedMissingAreas,
                relatedDimensions = question.relatedDimensions,
                evidence = listOf(
                    SourceEvidence(
                        id = "clarification-${question.id}-${state.clarificationRound + 1}",
                        text = trimmedAnswers,
                        sourceType = EvidenceSourceType.CLARIFICATION,
                        sourceRef = question.id,
                    ),
                ),
            )
        }
        val nextRequest =
            state.request.copy(
                clarificationHistory = state.request.clarificationHistory + exchanges,
            )
        val nextAssessment = readinessInvoker.assess(nextRequest)
        return BpmnReadinessState(
            request = nextRequest,
            assessment = nextAssessment,
            clarificationRound = state.clarificationRound + 1,
        )
    }

    @Action(
        description = "Terminate BPMN generation after the clarification round limit",
        pre = ["bpmnClarificationLimitReached"],
    )
    fun readinessBlockedAfterClarificationLimit(state: BpmnReadinessState): BpmnResult {
        val reportPath =
            readinessReportWriter.writeReport(
                originalInput = state.request.processDescription,
                assessment = state.assessment,
                outputFile = state.request.outputFile,
            )
        return BpmnResult(
            outputFile = state.request.outputFile,
            status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
            readinessReport = state.assessment,
            reportFile = reportPath,
        )
    }

    @Condition
    fun bpmnRequestReady(state: BpmnReadinessState): Boolean = state.assessment.verdict == ReadinessVerdict.READY

    @Condition
    fun assessmentReady(assessment: ProcessInputAssessment): Boolean = assessment.verdict == ReadinessVerdict.READY

    @Condition
    fun bpmnClarificationAvailable(state: BpmnReadinessState): Boolean = state.assessment.verdict ==
        ReadinessVerdict.NEEDS_CLARIFICATION &&
        state.clarificationRound < MAX_CLARIFICATION_ROUNDS &&
        state.assessment.clarificationQuestions.isNotEmpty()

    @Condition
    fun bpmnClarificationLimitReached(state: BpmnReadinessState): Boolean = state.assessment.verdict ==
        ReadinessVerdict.NEEDS_CLARIFICATION &&
        state.clarificationRound >= MAX_CLARIFICATION_ROUNDS

    private fun clarificationPrompt(state: BpmnReadinessState): String {
        val questions =
            state.assessment.clarificationQuestions
                .joinToString(separator = System.lineSeparator()) { question ->
                    "- ${question.questionText}"
                }
        return "BPMN clarification round ${state.clarificationRound + 1} of $MAX_CLARIFICATION_ROUNDS\n$questions"
    }

    private companion object {
        const val MAX_CLARIFICATION_ROUNDS = 3
    }
}
