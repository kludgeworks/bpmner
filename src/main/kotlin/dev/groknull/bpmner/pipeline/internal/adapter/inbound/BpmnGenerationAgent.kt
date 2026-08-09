/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.annotation.State
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.ActionRetryPolicy
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.hitl.FormBindingRequest
import com.embabel.agent.core.hitl.WaitFor
import com.embabel.agent.domain.io.UserInput
import com.embabel.ux.form.Form
import com.embabel.ux.form.RadioGroup
import com.embabel.ux.form.RadioOption
import com.embabel.ux.form.TextArea
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAligner
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.authoring.BpmnGenerationStatus
import dev.groknull.bpmner.authoring.BpmnOutlineGenerationException
import dev.groknull.bpmner.authoring.BpmnProcessGenerator
import dev.groknull.bpmner.authoring.BpmnRequestDraft
import dev.groknull.bpmner.authoring.BpmnRequestDrafter
import dev.groknull.bpmner.authoring.BpmnRequestResolutionPort
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.authoring.ValidatedOutline
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.GenerationMode
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.conformance.BpmnXsdValidationPort
import dev.groknull.bpmner.conformance.FinalValidatedBpmnXml
import dev.groknull.bpmner.conformance.ValidatedBpmnXml
import dev.groknull.bpmner.contract.BpmnContractExtractionException
import dev.groknull.bpmner.contract.ProcessContractExtractor
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.layout.BpmnLayoutCompletedEvent
import dev.groknull.bpmner.layout.BpmnLayoutPort
import dev.groknull.bpmner.layout.LayoutedBpmnXml
import dev.groknull.bpmner.readiness.BpmnClarificationAnswers
import dev.groknull.bpmner.readiness.BpmnReadinessAssessedEvent
import dev.groknull.bpmner.readiness.BpmnReadinessAssessmentException
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ClarificationExchange
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.repair.BpmnRepairer
import org.springframework.context.ApplicationEventPublisher
import java.io.File

@Agent(description = "Single idiomatic agent for happy-path BPMN generation")
internal class BpmnGenerationAgent(
    private val requestDrafter: BpmnRequestDrafter,
    private val requestResolver: BpmnRequestResolutionPort,
    private val readinessInvoker: BpmnReadinessInvoker,
    private val contractExtractor: ProcessContractExtractor,
    private val processGenerator: BpmnProcessGenerator,
    private val repairer: BpmnRepairer,
    private val layoutPort: BpmnLayoutPort,
    private val xsdValidationPort: BpmnXsdValidationPort,
    private val aligner: BpmnAligner,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Action
    fun draft(userInput: UserInput, ctx: OperationContext): BpmnRequestDraft {
        return requestDrafter.draftRequest(userInput, ctx)
    }

    @Action
    fun resolve(draft: BpmnRequestDraft): BpmnRequest {
        return requestResolver.resolveShellRequest(draft)
    }

    // Readiness assessment is fallible: the sub-agent throws when the model cannot produce a
    // structured verdict. Catching it here turns a process-killing throw into a state the
    // planner can route to a terminal, so the run reports why it stopped instead of going silent.
    @Action
    fun assessReadiness(request: BpmnRequest): ReadinessStage {
        val assessment = try {
            readinessInvoker.assess(request)
        } catch (e: BpmnReadinessAssessmentException) {
            return ReadinessFailed(request, e.message ?: "Readiness assessment failed")
        }
        publishReadinessAssessed(request, assessment)
        return Assessing(request, assessment, round = 0)
    }

    @Action
    fun startAssessing(request: BpmnRequest, assessment: ProcessInputAssessment): Assessing {
        return Assessing(request, assessment, round = 0)
    }

    @Action(clearBlackboard = true, actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
    fun reassess(state: AwaitingClarification, answers: BpmnClarificationAnswers): Assessing {
        val next = state.request.withClarification(answers, state.assessment)
        val assessment = readinessInvoker.assess(next)
        publishReadinessAssessed(next, assessment)
        return Assessing(next, assessment, state.round + 1)
    }

    // Readiness runs as its own ephemeral sub-process; only the orchestrator (right here) has
    // the outer processId — a listener resolving AgentProcess.get() later would not.
    private fun publishReadinessAssessed(request: BpmnRequest, assessment: ProcessInputAssessment) {
        eventPublisher.publishEvent(BpmnReadinessAssessedEvent(request, assessment, processId = AgentProcess.get()?.id))
    }

    @Action
    fun extractContract(ready: ReadyBpmnContext, ctx: OperationContext): ContractStage {
        return try {
            ContractReady(contractExtractor.extract(ready, ctx))
        } catch (e: BpmnContractExtractionException) {
            ContractFailed(ready.request, e.message ?: "Contract extraction failed")
        }
    }

    @Action
    fun createOutline(ready: ReadyBpmnContext, c: ValidatedProcessContract, ctx: OperationContext): OutlineStage {
        return try {
            OutlineReady(processGenerator.createOutline(ready, c, ctx))
        } catch (e: BpmnOutlineGenerationException) {
            OutlineFailed(ready.request, e.message ?: "Outline generation failed")
        }
    }

    @Action
    fun composeGraph(outline: ValidatedOutline): LaidOutProcessGraph {
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
    ): ValidationStage {
        val validated = repairer.validateInitial(ready, g, r, c, ctx)
        return if (validated.diagnostics.any { it.isBlocking }) {
            ValidationFailed(ready, validated)
        } else {
            ValidationPassed(validated)
        }
    }

    @Action(actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
    fun layout(ready: ReadyBpmnContext, validated: ValidatedBpmnXml): LayoutStage {
        val layoutedXml = layoutPort.layout(validated.xml)
        val layouted = LayoutedBpmnXml(definition = validated.definition, xml = layoutedXml)
        val xsdIssues = xsdValidationPort.validateDetailed(layouted.xml)
        if (xsdIssues.isNotEmpty()) {
            val details = xsdIssues.mapNotNull { it.message }.joinToString("; ").ifBlank { "Unknown XSD validation error" }
            // Layout is deterministic, so retrying cannot help; report the reason and stop.
            return LayoutFailed(ready.request, "Auto-layout produced structurally invalid BPMN: $details", layouted.xml)
        }
        // Publish the laid-out (DI-bearing) XML so telemetry can forward a LAYOUT_COMPLETE
        // snapshot over the SSE channel, enabling the web client to switch from its client-side
        // preview layout to the canonical server geometry (ARCH ADR-ss-007).
        eventPublisher.publishEvent(BpmnLayoutCompletedEvent(layouted.xml, processId = AgentProcess.get()?.id))
        return LayoutReady(FinalValidatedBpmnXml(definition = layouted.definition, xml = layouted.xml))
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

    // Critique gate (ADR-001): alignment is not a throwing step.
    // PASSED verdict -> write file; FAILED verdict -> return ALIGNMENT_FAILED.
    // Typed inputs alone gate the action; the verdict branch is internal to avoid GOAP planning lock.
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

// Sealed supertype for polymorphic state returns.
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

    // Branch: READY -> Ready; not-ready + INTERACTIVE + rounds left -> ask;
    // SINGLE_SHOT or rounds exhausted -> Blocked.
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

    // Pauses the process into WAITING and asks for typed answers.
    @Action
    fun ask(): BpmnClarificationAnswers {
        WaitFor.awaitable(clarificationFormFrom(assessment))
        error("Clarification form returned without parking the process")
    }
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

@State
data class ReadinessFailed(
    val request: BpmnRequest,
    val detail: String,
) : ReadinessStage {
    @AchievesGoal(
        description = "Terminate with readiness failure",
        export = Export(
            name = "generateBpmn",
            startingInputTypes = [UserInput::class, BpmnRequest::class, ProcessInputAssessment::class],
        ),
    )
    @Action
    fun terminate(): BpmnResult = BpmnResult(
        outputFile = request.outputFile,
        status = BpmnGenerationStatus.READINESS_FAILED,
        failureDetail = detail,
    )
}

// Sealed supertype for polymorphic contract-extraction returns
sealed interface ContractStage

@State
data class ContractReady(val contract: ValidatedProcessContract) : ContractStage {
    @Action fun proceed(): ValidatedProcessContract = contract
}

@State
data class ContractFailed(
    val request: BpmnRequest,
    val detail: String,
) : ContractStage {
    @AchievesGoal(
        description = "Terminate with contract extraction failure",
        export = Export(
            name = "generateBpmn",
            startingInputTypes = [UserInput::class, BpmnRequest::class, ProcessInputAssessment::class],
        ),
    )
    @Action
    fun terminate(): BpmnResult = BpmnResult(
        outputFile = request.outputFile,
        status = BpmnGenerationStatus.CONTRACT_FAILED,
        failureDetail = detail,
    )
}

// Sealed supertype for polymorphic outline-generation returns
sealed interface OutlineStage

@State
data class OutlineReady(val outline: ValidatedOutline) : OutlineStage {
    @Action fun proceed(): ValidatedOutline = outline
}

@State
data class OutlineFailed(
    val request: BpmnRequest,
    val detail: String,
) : OutlineStage {
    @AchievesGoal(
        description = "Terminate with outline generation failure",
        export = Export(
            name = "generateBpmn",
            startingInputTypes = [UserInput::class, BpmnRequest::class, ProcessInputAssessment::class],
        ),
    )
    @Action
    fun terminate(): BpmnResult = BpmnResult(
        outputFile = request.outputFile,
        status = BpmnGenerationStatus.OUTLINE_FAILED,
        failureDetail = detail,
    )
}

// Sealed supertype for polymorphic layout returns
sealed interface LayoutStage

@State
data class LayoutReady(val laidOut: FinalValidatedBpmnXml) : LayoutStage {
    @Action fun proceed(): FinalValidatedBpmnXml = laidOut
}

@State
data class LayoutFailed(
    val request: BpmnRequest,
    val detail: String,
    val xml: String,
) : LayoutStage {
    @AchievesGoal(
        description = "Terminate with layout failure",
        export = Export(
            name = "generateBpmn",
            startingInputTypes = [UserInput::class, BpmnRequest::class, ProcessInputAssessment::class],
        ),
    )
    @Action
    fun terminate(): BpmnResult = BpmnResult(
        outputFile = request.outputFile,
        status = BpmnGenerationStatus.LAYOUT_FAILED,
        xml = xml,
        failureDetail = detail,
    )
}

// Sealed supertype for polymorphic validation returns
sealed interface ValidationStage

@State
data class ValidationPassed(val validated: ValidatedBpmnXml) : ValidationStage {
    @Action fun proceed(): ValidatedBpmnXml = validated
}

@State
data class ValidationFailed(
    val ready: ReadyBpmnContext,
    val validated: ValidatedBpmnXml,
) : ValidationStage {
    @AchievesGoal(
        description = "Terminate with validation failure",
        export = Export(
            name = "generateBpmn",
            startingInputTypes = [UserInput::class, BpmnRequest::class, ProcessInputAssessment::class],
        ),
    )
    @Action
    fun terminate(): BpmnResult = BpmnResult(
        outputFile = ready.request.outputFile,
        status = BpmnGenerationStatus.VALIDATION_FAILED,
        xml = validated.xml,
        validationDiagnostics = validated.diagnostics.filter { it.isBlocking },
    )
}

private const val MAX_ROUNDS = 3 // max clarification rounds before Blocked
private const val MIN_CLARIFICATION_OPTIONS = 2
private const val MAX_CLARIFICATION_OPTIONS = 4

private fun clarificationFormFrom(assessment: ProcessInputAssessment): FormBindingRequest<BpmnClarificationAnswers> {
    val question = assessment.clarificationQuestions.firstOrNull()
    val prompt = question?.questionText ?: assessment.rationale.ifBlank { "Please provide clarification." }
    val control = if (
        question != null &&
        question.options.size in MIN_CLARIFICATION_OPTIONS..MAX_CLARIFICATION_OPTIONS
    ) {
        RadioGroup(
            label = "Answer",
            options = question.options.map { RadioOption(label = it, value = it) },
            required = true,
            id = "answers",
        )
    } else {
        TextArea(
            label = "Answer",
            placeholder = "Enter your answer…",
            rows = 4,
            required = true,
            id = "answers",
        )
    }
    return FormBindingRequest(
        Form(title = prompt, controls = listOf(control), id = "bpmn-clarification"),
        BpmnClarificationAnswers::class.java,
    )
}

private fun BpmnRequest.withClarification(
    answers: BpmnClarificationAnswers,
    assessment: ProcessInputAssessment,
): BpmnRequest {
    val exchange =
        assessment.clarificationQuestions.firstOrNull()?.let { question ->
            ClarificationExchange(
                questionId = question.id,
                questionText = question.questionText,
                answerText = answers.answers,
            )
        } ?: ClarificationExchange(
            questionId = "generic",
            questionText = assessment.rationale.ifBlank { "Please provide clarification." },
            answerText = answers.answers,
        )
    return this.copy(
        clarificationHistory = this.clarificationHistory + exchange,
    )
}
