package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.api.common.PromptRunner
import com.embabel.chat.Message
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnLocalRepairOutcome
import dev.groknull.bpmner.core.BpmnRepairAttempt
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
    ) : BpmnRepairResult()

    data object NotApplicable : BpmnRepairResult()

    data class LocalAttemptedNoChange(
        val outcome: BpmnLocalRepairOutcome,
    ) : BpmnRepairResult()

    data class TerminalFailure(
        val reason: String,
    ) : BpmnRepairResult()
}
