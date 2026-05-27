/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.chat.Message
import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.validation.BpmnDiagnostic
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
    ): String

    fun fullRepairFeedback(
        attempt: BpmnRepairAttempt,
        diagnostics: List<BpmnDiagnostic> = attempt.diagnostics,
    ): String

    fun lintRuleDocsPrompt(diagnostics: List<BpmnDiagnostic>): PromptContributor?
}

@SecondaryPort
internal fun interface BpmnPatchApplicationPort {
    fun apply(
        definition: BpmnDefinition,
        patch: BpmnRepairPatch,
    ): PatchApplicationResult
}
