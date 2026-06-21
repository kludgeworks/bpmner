/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.orchestration.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.annotation.State
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.ActionRetryPolicy
import com.embabel.agent.core.hitl.WaitFor
import com.embabel.agent.domain.io.UserInput
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAligner
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.authoring.BpmnGenerationStatus
import dev.groknull.bpmner.authoring.BpmnProcessGenerator
import dev.groknull.bpmner.authoring.BpmnRequestDraft
import dev.groknull.bpmner.authoring.BpmnRequestDrafter
import dev.groknull.bpmner.authoring.BpmnRequestResolver
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.authoring.ValidatedOutline
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.GenerationMode
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.conformance.BpmnXsdValidationPort
import dev.groknull.bpmner.conformance.FinalValidatedBpmnXml
import dev.groknull.bpmner.conformance.ValidatedBpmnXml
import dev.groknull.bpmner.contract.ProcessContractExtractor
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.layout.BpmnLayoutPort
import dev.groknull.bpmner.layout.LayoutedBpmnXml
import dev.groknull.bpmner.readiness.BpmnClarificationAnswers
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ClarificationExchange
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.repair.BpmnRepairer
import java.io.File

@Agent(description = "Single idiomatic agent for happy-path BPMN generation")
internal class BpmnGenerationAgent(
    private val requestDrafter: BpmnRequestDrafter,
    private val requestResolver: BpmnRequestResolver,
    private val readinessInvoker: BpmnReadinessInvoker,
    private val contractExtractor: ProcessContractExtractor,
    private val processGenerator: BpmnProcessGenerator,
    private val repairer: BpmnRepairer,
    private val layoutPort: BpmnLayoutPort,
    private val xsdValidationPort: BpmnXsdValidationPort,
    private val aligner: BpmnAligner,
) {
    @Action
    fun draft(userInput: UserInput, ctx: OperationContext): BpmnRequestDraft {
        return requestDrafter.draftRequest(userInput, ctx)
    }

    @Action
    fun resolve(draft: BpmnRequestDraft): BpmnRequest {
        return requestResolver.resolveShellRequest(draft)
    }

    @Action
    fun assessReadiness(request: BpmnRequest): ProcessInputAssessment {
        return readinessInvoker.assess(request)
    }

    @Action
    fun startAssessing(request: BpmnRequest, assessment: ProcessInputAssessment): Assessing {
        return Assessing(request, assessment, round = 0)
    }

    @Action(clearBlackboard = true, actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
    fun reassess(state: AwaitingClarification, answers: BpmnClarificationAnswers): Assessing {
        val next = state.request.withClarification(answers, state.assessment)
        return Assessing(next, readinessInvoker.assess(next), state.round + 1)
    }

    @Action
    fun extractContract(ready: ReadyBpmnContext, ctx: OperationContext): ValidatedProcessContract {
        return contractExtractor.extract(ready, ctx)
    }

    @Action
    fun createOutline(ready: ReadyBpmnContext, c: ValidatedProcessContract, ctx: OperationContext): ValidatedOutline {
        return processGenerator.createOutline(ready, c, ctx)
    }

    @Action fun composeGraph(outline: ValidatedOutline): LaidOutProcessGraph {
        return processGenerator.composeGraph(outline)
    }

    @Action fun render(ready: ReadyBpmnContext, graph: LaidOutProcessGraph): RenderedBpmn {
        return processGenerator.render(ready, graph)
    }

    @Action
    fun validate(
        ready: ReadyBpmnContext,
        g: LaidOutProcessGraph,
        r: RenderedBpmn,
        c: ValidatedProcessContract,
        ctx: ActionContext,
    ): ValidatedBpmnXml {
        return repairer.validateInitial(ready, g, r, c, ctx)
    }

    @Action(actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
    fun layout(validated: ValidatedBpmnXml): FinalValidatedBpmnXml {
        val layoutedXml = layoutPort.layout(validated.xml)
        val layouted = LayoutedBpmnXml(definition = validated.definition, xml = layoutedXml)
        val xsdIssues = xsdValidationPort.validateDetailed(layouted.xml)
        if (xsdIssues.isNotEmpty()) {
            val details = xsdIssues.mapNotNull { it.message }.joinToString("; ").ifBlank { "Unknown XSD validation error" }
            error("Auto-layout produced structurally invalid BPMN: $details")
        }
        return FinalValidatedBpmnXml(definition = layouted.definition, xml = layouted.xml)
    }

    @Action(actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
    fun align(
        ready: ReadyBpmnContext,
        c: ValidatedProcessContract,
        x: FinalValidatedBpmnXml,
        ctx: OperationContext,
    ): BpmnAlignmentReport {
        return aligner.align(ready, c, x, ctx)
    }

    // Critique gate (doc §3.2, §1 G5, §9): alignment is not a throwing step.
    // PASSED verdict (ALIGNED / PARTIALLY_ALIGNED) → write file, return GENERATED.
    // FAILED verdict → return typed ALIGNMENT_FAILED carrying the report, no file write.
    // A single action with verdict check inside satisfies the GOAP runtime planner:
    // @Condition + pre= on two separate goal actions causes both conditions to evaluate FALSE
    // when BpmnAlignmentReport is not yet on the blackboard, making the planner stuck.
    // (doc §8 risk #1 mitigation: typed inputs alone gate the action; verdict branch is internal.)
    @AchievesGoal(
        description = "Generate a complete BPMN definition from user input",
        export = Export(
            name = "generateBpmn",
            startingInputTypes = [UserInput::class, BpmnRequest::class, ProcessInputAssessment::class],
        ),
    )
    @Action(actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
    fun finish(
        ready: ReadyBpmnContext,
        x: FinalValidatedBpmnXml,
        report: BpmnAlignmentReport,
    ): BpmnResult {
        if (report.verdict == AlignmentVerdict.FAILED) {
            // doc §3.2: FAILED → typed BpmnResult(status=ALIGNMENT_FAILED), no file write.
            return BpmnResult(
                outputFile = ready.request.outputFile,
                status = BpmnGenerationStatus.ALIGNMENT_FAILED,
                xml = x.xml,
                alignmentReport = report,
            )
        }
        // PASSED (ALIGNED / PARTIALLY_ALIGNED) → write file, return GENERATED.
        ready.request.outputFile?.takeIf { it.isNotBlank() }?.let { filePath ->
            val file = File(filePath)
            file.parentFile?.mkdirs()
            file.writeText(x.xml, Charsets.UTF_8)
        }
        return BpmnResult(
            outputFile = ready.request.outputFile,
            status = BpmnGenerationStatus.GENERATED,
            xml = x.xml,
            alignmentReport = report,
        )
    }
}

// Sealed supertype for polymorphic state returns (lore: "parent interface").
sealed interface ReadinessStage

// State that wraps ReadyBpmnContext for the state machine
@State
data class Ready(val ready: ReadyBpmnContext) : ReadinessStage {
    @Action fun proceed(): ReadyBpmnContext = ready // feeds existing downstream chain
}

@State
data class Assessing(
    val request: BpmnRequest,
    val assessment: ProcessInputAssessment,
    val round: Int, // clarification rounds completed so far
) : ReadinessStage {

    // Branch: READY → Ready; not-ready + INTERACTIVE + rounds left → ask;
    // SINGLE_SHOT or rounds exhausted → Blocked. clearBlackboard=true so the
    // loop can re-enter Assessing after an answer (lore §4.19.4).
    @Action(clearBlackboard = true)
    fun assess(): ReadinessStage = when {
        assessment.verdict == ReadinessVerdict.READY ->
            Ready(ReadyBpmnContext(request, assessment))
        request.mode == GenerationMode.SINGLE_SHOT || round >= MAX_ROUNDS ->
            Blocked(request, assessment) // SINGLE_SHOT blocks immediately
        else -> AwaitingClarification(request, assessment, round)
    }
}

@State
data class AwaitingClarification(
    val request: BpmnRequest,
    val assessment: ProcessInputAssessment,
    val round: Int,
) : ReadinessStage {

    // Pauses the process into WAITING and asks for typed answers (lore §4.19.9).
    @Action
    fun ask(): BpmnClarificationAnswers = WaitFor.formSubmission(promptFrom(assessment), BpmnClarificationAnswers::class.java)
}

@State
data class Blocked(
    val request: BpmnRequest,
    val assessment: ProcessInputAssessment,
) : ReadinessStage {
    @AchievesGoal(
        description = "Terminate with needs clarification",
        export = Export(
            name = "generateBpmn",
            startingInputTypes = [UserInput::class, BpmnRequest::class, ProcessInputAssessment::class],
        ),
    )
    @Action
    fun terminate(): BpmnResult = BpmnResult(
        outputFile = request.outputFile,
        status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
        readinessReport = assessment,
    )
}

private const val MAX_ROUNDS = 3 // max clarification rounds before Blocked

private fun promptFrom(assessment: ProcessInputAssessment): String {
    val questions = assessment.clarificationQuestions
    return if (questions.isEmpty()) {
        assessment.rationale.ifBlank { "Please provide clarification." }
    } else {
        questions.joinToString("\n") { it.questionText }
    }
}

private fun BpmnRequest.withClarification(
    answers: BpmnClarificationAnswers,
    assessment: ProcessInputAssessment,
): BpmnRequest {
    val genericExchange =
        ClarificationExchange(
            questionId = "generic",
            questionText = assessment.rationale.ifBlank { "Please provide clarification." },
            answerText = answers.answers,
        )
    val newExchanges =
        assessment.clarificationQuestions.map { question ->
            ClarificationExchange(
                questionId = question.id,
                questionText = question.questionText,
                answerText = answers.answers,
            )
        }
    val exchangesToAdd =
        when {
            newExchanges.isEmpty() -> listOf(genericExchange)
            else -> newExchanges
        }
    return this.copy(
        clarificationHistory = this.clarificationHistory + exchangesToAdd,
    )
}
