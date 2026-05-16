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

import com.embabel.agent.api.common.PromptRunner
import com.embabel.chat.Message
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.repair.BpmnLocalFixSummary
import dev.groknull.bpmner.repair.BpmnLocalRepairOutcome
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import org.jmolecules.architecture.hexagonal.SecondaryPort
import org.springframework.core.Ordered

@SecondaryPort
internal interface BpmnRepairStrategy : Ordered {
    fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult
}

internal data class BpmnRepairStrategyContext(
    val attempt: BpmnRepairAttempt,
    val promptRunner: PromptRunner,
    val localOutcome: BpmnLocalRepairOutcome = BpmnLocalRepairOutcome.EMPTY,
)

internal sealed class BpmnRepairResult {
    data class Repaired(
        val definition: BpmnDefinition,
        val promptText: String,
        val messages: List<Message>,
        val localFixSummary: BpmnLocalFixSummary? = null,
    ) : BpmnRepairResult()

    data object NotApplicable : BpmnRepairResult()

    data class LocalAttemptedNoChange(
        val outcome: BpmnLocalRepairOutcome,
    ) : BpmnRepairResult()

    data class TerminalFailure(
        val reason: String,
    ) : BpmnRepairResult()
}
