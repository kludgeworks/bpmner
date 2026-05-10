package dev.groknull.bpmner.repair.internal

import com.embabel.agent.api.common.PromptRunner
import dev.groknull.bpmner.core.BpmnRepairAttempt

data class BpmnRepairStrategyContext(
    val attempt: BpmnRepairAttempt,
    val promptRunner: PromptRunner,
)

internal interface BpmnRepairStrategy {
    fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult
}
