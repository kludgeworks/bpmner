/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.chat.Message
import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.repair.BpmnLocalRepairOutcome
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
