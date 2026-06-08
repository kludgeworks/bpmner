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
 * Marker type produced by [BpmnGenerationGateAgent.openReadinessGate].
 *
 * ## Why a distinct return type?
 *
 * The GOAP planner skips any action whose output type is already on the blackboard ([ProcessInputAssessment]
 * is in `startingInputTypes`), so an action that returns [ProcessInputAssessment] would never execute and
 * its `post` conditions would never be registered.
 *
 * ## Why must [BpmnGenerationGateAgent.approveReadyRequest] accept it?
 *
 * The planner's forward-pass optimisation **prunes** actions whose output type is not consumed by any
 * subsequent action. If [ReadinessGate] were produced but never taken as a parameter, the forward pass
 * would remove [BpmnGenerationGateAgent.openReadinessGate] from the plan; its string effects
 * (`assessmentReady`, etc.) would therefore never be posted, leaving [BpmnGenerationGateAgent.approveReadyRequest]
 * unreachable to the static validator. Declaring [ReadinessGate] as a parameter on [BpmnGenerationGateAgent.approveReadyRequest]
 * anchors it in the plan without changing any runtime semantics — the parameter value is intentionally ignored
 * in the method body.
 */
internal data class ReadinessGate(val assessment: ProcessInputAssessment)

/**
 * Resolves shell `UserInput` into a [BpmnRequest] and gates downstream generation on readiness.
 *
 * Readiness is assessed once, by the single producer [dev.groknull.bpmner.readiness.internal.adapter.inbound.BpmnReadinessAgent]
 * `assessReadiness` (which yields a [ProcessInputAssessment]). The GOAP planner runs that action
 * in-process when no assessment is on the blackboard (the shell path) and skips it when one is
 * already present (the seeded `generate(request, assessment)` path) — so there is a single path to
 * [ReadyBpmnContext] via [approveReadyRequest], no nested readiness sub-process, and no ambiguity.
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
        description = "Open the readiness gate from a pre-seeded assessment",
        post = ["assessmentReady", "clarificationAvailable", "clarificationBlocked"],
    )
    fun openReadinessGate(assessment: ProcessInputAssessment): ReadinessGate = ReadinessGate(assessment)

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
        // gate is a planner type-token only — its value is intentionally unused.
        // openReadinessGate produces it and posts the "assessmentReady" string condition;
        // declaring it here prevents the forward-pass optimiser from pruning openReadinessGate
        // (which would remove its post conditions from the world state and leave this goal unreachable).
        @Suppress("UNUSED_PARAMETER") gate: ReadinessGate,
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
    }
}
