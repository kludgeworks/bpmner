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
import dev.groknull.bpmner.api.GenerationMode
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
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessReportWriter
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import org.jmolecules.architecture.hexagonal.Application
import org.slf4j.LoggerFactory

/**
 * Resolves shell `UserInput` into a [BpmnRequest] and gates downstream generation on readiness.
 *
 * ## Why this agent assesses readiness itself
 *
 * Readiness assessment lives in [dev.groknull.bpmner.readiness.internal.adapter.inbound.BpmnReadinessAgent]
 * (`assessReadiness`), but that is a *separate* agent and therefore invisible to the per-agent static
 * GOAP validator (`GoapPathToCompletionValidator`), which plans over this agent's actions in isolation
 * and **ignores `startingInputTypes`**. Without an in-scope, non-cyclic producer of [ProcessInputAssessment]
 * the validator can never seed one — [applyClarificationAnswers] produces a `ProcessInputAssessment` but
 * also *requires* one, so it cannot bootstrap — and every goal that needs readiness is reported
 * `NO_PATH_TO_GOAL`.
 *
 * [assessRequestReadiness] closes that gap: it produces a [ProcessInputAssessment] from a bare
 * [BpmnRequest] (delegating the real work to the [BpmnReadinessInvoker] port, which runs the readiness
 * agent as a sub-process). The planner runs it on the shell path when no assessment is on the blackboard,
 * and skips it on the seeded `generate(request, assessment)` path because its `assessment` output is
 * already present.
 *
 * The interactive clarification loop ([askForClarification] / [applyClarificationAnswers]) is gated on
 * [GenerationMode.INTERACTIVE]; `SINGLE_SHOT` (and therefore ephemeral) requests cannot wait for input,
 * so a `NEEDS_CLARIFICATION` verdict terminates immediately via [readinessBlocked].
 */
@Application
@Agent(description = "Resolve shell BPMN requests and gate generation on readiness")
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

    @Action(
        description = "Assess a resolved BPMN request for process readiness",
        // This is the in-scope, non-cyclic producer of ProcessInputAssessment that lets the per-agent static
        // GOAP validator plan a path from a bare request to approval. (The validator plans over this agent's
        // actions in isolation and ignores startingInputTypes, so without it the only in-scope producer is
        // applyClarificationAnswers — which itself requires an assessment, a cycle with no seed — and every
        // readiness goal is reported NO_PATH_TO_GOAL.) It advertises every gate condition it can establish so
        // the planner can reach approveReadyRequest, askForClarification, or readinessBlocked from here; the
        // actual verdict is recomputed at runtime by the @Condition methods against the returned assessment.
        // The `assessment` output carries a FALSE precondition, so the planner skips it whenever an assessment
        // is already on the blackboard (the seeded `generate(request, assessment)` path).
        post = ["assessmentReady", "clarificationAvailable", "clarificationBlocked"],
        // Costlier than the canonical cross-agent producer (BpmnReadinessAgent.assessReadiness, cost 0.0) so
        // the platform's multi-agent planner always prefers that one — this action exists only to make the
        // single-agent static validation provable and is never the cheapest path at runtime, so its body
        // (which would otherwise re-enter the readiness invoker) is not executed during real planning.
        cost = READINESS_BOOTSTRAP_COST,
    )
    fun assessRequestReadiness(request: BpmnRequest): ProcessInputAssessment = readinessInvoker.assess(request)

    @AchievesGoal(
        description = "Resolve and approve a BPMN generation request for downstream generation",
        export =
        Export(
            name = "prepareBpmnGeneration",
            remote = false,
            startingInputTypes = [UserInput::class, BpmnRequest::class, ProcessInputAssessment::class],
        ),
    )
    @Action(description = "Approve a ready BPMN request for contract extraction", pre = ["assessmentReady"])
    fun approveReadyRequest(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): ReadyBpmnContext = ReadyBpmnContext(request = request, assessment = assessment)

    @Action(
        description = "Ask the user for BPMN readiness clarifications",
        pre = ["clarificationAvailable"],
        canRerun = true,
    )
    fun askForClarification(
        assessment: ProcessInputAssessment,
        request: BpmnRequest,
    ): BpmnClarificationAnswers = WaitFor.formSubmission(
        clarificationPrompt(assessment, request.clarificationRoundCount),
        BpmnClarificationAnswers::class.java,
    )

    @Action(
        description = "Apply BPMN clarification answers and reassess readiness",
        post = ["assessmentReady", "clarificationAvailable", "clarificationBlocked"],
        // Input-gated on BpmnClarificationAnswers (only applicable once the form is submitted) and
        // re-runnable across rounds. No trigger: a trigger makes the action reactive-only and removes
        // it from goal-directed planning, so the planner could not plan a path through the loop.
        canRerun = true,
        // Slightly more expensive than the initial assessReadiness (cost 0.0) so the planner prefers
        // the direct assessment when both are applicable.
        cost = CLARIFICATION_REASSESS_COST,
    )
    fun applyClarificationAnswers(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
        answers: BpmnClarificationAnswers,
        context: OperationContext,
    ): ProcessInputAssessment {
        require(assessment.clarificationQuestions.isNotEmpty()) {
            "Cannot apply clarification answers: no pending questions."
        }
        val trimmedAnswers = answers.answers.trim()
        require(trimmedAnswers.isNotEmpty()) { "Clarification answers must not be blank." }
        val round = request.clarificationRoundCount
        // The shell form accepts one consolidated response; each pending question records that answer
        // so the next readiness pass can decide which gaps were actually resolved.
        val exchanges = assessment.clarificationQuestions.map { question ->
            ClarificationExchange(
                questionId = question.id,
                questionText = question.questionText,
                answerText = trimmedAnswers,
                relatedMissingAreas = question.relatedMissingAreas,
                relatedDimensions = question.relatedDimensions,
                evidence = listOf(
                    SourceEvidence(
                        id = "clarification-${question.id}-${round + 1}",
                        text = trimmedAnswers,
                        sourceType = EvidenceSourceType.CLARIFICATION,
                        sourceRef = question.id,
                    ),
                ),
            )
        }
        val nextRequest = request.copy(clarificationHistory = request.clarificationHistory + exchanges)
        val nextAssessment = readinessInvoker.assess(nextRequest)
        context.bind("request", nextRequest)
        logger.info(
            "Readiness reassessed after clarification. verdict={}, overallScore={}, clarificationRound={}",
            nextAssessment.verdict,
            nextAssessment.overallScore,
            nextRequest.clarificationRoundCount,
        )
        return nextAssessment
    }

    @Action(
        description = "Terminate BPMN generation when readiness clarification cannot resolve",
        pre = ["clarificationBlocked"],
    )
    fun readinessBlocked(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): BpmnResult {
        val reportPath =
            readinessReportWriter.writeReport(
                originalInput = request.processDescription,
                assessment = assessment,
                outputFile = request.outputFile,
            )
        return BpmnResult(
            outputFile = request.outputFile,
            status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
            readinessReport = assessment,
            reportFile = reportPath,
        )
    }

    @Condition
    fun assessmentReady(assessment: ProcessInputAssessment): Boolean = assessment.verdict == ReadinessVerdict.READY

    @Condition
    fun clarificationAvailable(
        assessment: ProcessInputAssessment,
        request: BpmnRequest,
    ): Boolean = assessment.verdict == ReadinessVerdict.NEEDS_CLARIFICATION &&
        request.mode == GenerationMode.INTERACTIVE &&
        request.clarificationRoundCount < MAX_CLARIFICATION_ROUNDS &&
        assessment.clarificationQuestions.isNotEmpty()

    @Condition
    fun clarificationBlocked(
        assessment: ProcessInputAssessment,
        request: BpmnRequest,
    ): Boolean {
        if (assessment.verdict != ReadinessVerdict.NEEDS_CLARIFICATION) return false
        val singleShot = request.mode == GenerationMode.SINGLE_SHOT
        val roundsExhausted = request.clarificationRoundCount >= MAX_CLARIFICATION_ROUNDS
        return singleShot || roundsExhausted
    }

    private fun clarificationPrompt(
        assessment: ProcessInputAssessment,
        round: Int,
    ): String {
        val questions =
            assessment.clarificationQuestions
                .joinToString(separator = System.lineSeparator()) { question ->
                    "- ${question.questionText}"
                }
        return "BPMN clarification round ${round + 1} of $MAX_CLARIFICATION_ROUNDS\n$questions"
    }

    private val BpmnRequest.clarificationRoundCount: Int
        get() = clarificationHistory
            .flatMap { it.evidence }
            .mapNotNull { it.id.substringAfterLast('-').toIntOrNull() }
            .maxOrNull() ?: 0

    private companion object {
        const val MAX_CLARIFICATION_ROUNDS = 3
        const val CLARIFICATION_REASSESS_COST = 0.5

        // Strictly greater than BpmnReadinessAgent.assessReadiness (0.0) so the multi-agent planner never
        // executes the in-scope bootstrap; it is present purely to make per-agent static validation provable.
        const val READINESS_BOOTSTRAP_COST = 1.0
    }
}
