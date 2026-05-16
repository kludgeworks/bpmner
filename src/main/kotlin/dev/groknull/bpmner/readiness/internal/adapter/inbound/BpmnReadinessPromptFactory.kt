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

package dev.groknull.bpmner.readiness.internal.adapter.inbound

import dev.groknull.bpmner.core.BpmnReadinessConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.readiness.ProcessInputAssessment

internal class BpmnReadinessPromptFactory(
    private val config: BpmnReadinessConfig,
) {
    fun prompt(request: BpmnRequest): String =
        buildString {
            appendLine("Return only a structured ${ProcessInputAssessment::class.simpleName} object.")
            appendLine()
            appendLine("Assess whether the source text is ready for BPMN process generation.")
            appendLine("Do not invent actors, triggers, end states, exceptions, artifacts, or activities.")
            appendLine("Mark unsupported facts as missing rather than filling gaps from assumptions.")
            appendLine("Ground evidence only in exact or narrowly paraphrased source text.")
            appendLine()
            appendLine("Verdict rules:")
            appendLine("- READY when overallScore >= ${config.readyThreshold}.")
            appendLine(
                "- NEEDS_CLARIFICATION when overallScore is " +
                    "${config.clarificationThreshold}..${config.readyThreshold - 1}.",
            )
            appendLine("- NOT_A_PROCESS when overallScore < ${config.clarificationThreshold}.")
            appendLine("- NOT_A_PROCESS if the input is not a repeatable workflow.")
            appendLine()
            appendLine("Score every readiness dimension:")
            ReadinessDimension.entries.forEach { appendLine("- ${it.name}") }
            appendLine()
            appendLine("Use missing process areas only from this vocabulary:")
            MissingProcessArea.entries.forEach { appendLine("- ${it.name}") }
            appendLine()
            appendLine("Clarification questions:")
            appendLine("- Ask at most ${config.maxClarificationQuestions} questions.")
            appendLine("- Make each question specific and answerable.")
            appendLine("- Tie every question to relatedDimensions and relatedMissingAreas.")
            appendLine()
            appendLine("Original BPMN request text:")
            appendLine(request.processDescription)
            if (request.clarificationHistory.isNotEmpty()) {
                appendLine()
                appendLine("Prior clarification answers:")
                request.clarificationHistory.forEach {
                    appendLine("- [${it.questionId}] Q: ${it.questionText}")
                    appendLine("  A: ${it.answerText}")
                }
                appendLine()
                appendLine("Re-assess readiness using both the original text and clarification answers.")
            }
        }
}
