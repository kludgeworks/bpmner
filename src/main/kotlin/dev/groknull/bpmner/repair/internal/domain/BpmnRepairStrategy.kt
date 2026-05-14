package dev.groknull.bpmner.repair.internal.domain
import dev.groknull.bpmner.core.BpmnDefinition


import com.embabel.agent.api.common.PromptRunner
import com.embabel.chat.Message
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
