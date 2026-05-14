package dev.groknull.bpmner.repair.internal.domain

import com.embabel.chat.Message
import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.repair.BpmnLocalRepairOutcome
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
internal interface BpmnRepairPromptPort {
    fun initialMessages(
        request: BpmnRequest,
        definition: BpmnDefinition,
    ): List<Message>

    fun patchFeedback(
        definition: BpmnDefinition,
        diagnostics: List<BpmnDiagnostic>,
        localOutcome: BpmnLocalRepairOutcome = BpmnLocalRepairOutcome.EMPTY,
    ): String

    fun fullRepairFeedback(
        attempt: BpmnRepairAttempt,
        diagnostics: List<BpmnDiagnostic> = attempt.diagnostics,
        localOutcome: BpmnLocalRepairOutcome = BpmnLocalRepairOutcome.EMPTY,
    ): String

    fun lintRuleDocsPrompt(diagnostics: List<BpmnDiagnostic>): PromptContributor?
}

@SecondaryPort
internal interface BpmnPatchApplicationPort {
    fun apply(
        definition: BpmnDefinition,
        patch: BpmnRepairPatch,
    ): PatchApplicationResult
}
