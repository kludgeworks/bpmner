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

import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.repair.BpmnLocalRepairOutcome
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnRepairScope
import dev.groknull.bpmner.validation.RepairKind
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Service
@Component
internal class LlmPatchRepairStrategy(
    private val promptFactory: BpmnRepairPromptPort,
    private val patchApplier: BpmnPatchApplicationPort,
) : BpmnRepairStrategy {
    override fun getOrder(): Int = 200

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val candidates =
            context.attempt.evaluation.diagnostics.filter { d ->
                eligibleForLlm(d, context.localOutcome) &&
                    (d.repairScope == BpmnRepairScope.OUTLINE || d.repairScope == BpmnRepairScope.PHASE)
            }
        if (candidates.isEmpty()) return BpmnRepairResult.NotApplicable

        val feedback = promptFactory.patchFeedback(context.attempt.definition, candidates, context.localOutcome)
        val patch = context.promptRunner.createObject(feedback, BpmnRepairPatch::class.java)

        return when (val application = patchApplier.apply(context.attempt.definition, patch)) {
            is PatchApplicationResult.Success -> {
                BpmnRepairResult.Repaired(
                    definition = application.definition,
                    promptText = feedback,
                    messages =
                        context.attempt.messages + UserMessage(feedback) +
                            AssistantMessage(patch.toString()),
                )
            }

            is PatchApplicationResult.Failure -> {
                LoggerFactory
                    .getLogger(this::class.java)
                    .warn("LLM patch application failed, falling back: {}", application.reason)
                BpmnRepairResult.NotApplicable
            }

            PatchApplicationResult.NoOp -> {
                BpmnRepairResult.NotApplicable
            }
        }
    }
}

@Service
@Component
internal class FullLlmRewriteRepairStrategy(
    private val promptFactory: BpmnRepairPromptPort,
) : BpmnRepairStrategy {
    override fun getOrder(): Int = 300

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val candidates =
            context.attempt.evaluation.diagnostics.filter { d ->
                eligibleForLlm(d, context.localOutcome)
            }
        if (candidates.isEmpty()) return BpmnRepairResult.NotApplicable

        val feedback = promptFactory.fullRepairFeedback(context.attempt, candidates, context.localOutcome)
        val repaired = context.promptRunner.createObject(feedback, BpmnDefinition::class.java)

        return BpmnRepairResult.Repaired(
            definition = repaired,
            promptText = feedback,
            messages =
                context.attempt.messages + UserMessage(feedback) + AssistantMessage(repaired.toString()),
        )
    }
}

private fun eligibleForLlm(
    diagnostic: BpmnDiagnostic,
    localOutcome: BpmnLocalRepairOutcome,
): Boolean {
    val kind = diagnostic.kind
    val routedToLlm = kind == null || kind == RepairKind.LLM_MODEL_PATCH || kind == RepairKind.LLM_XML_REWRITE
    val failedLocally = localOutcome.matches(diagnostic) != null
    return routedToLlm || failedLocally
}
