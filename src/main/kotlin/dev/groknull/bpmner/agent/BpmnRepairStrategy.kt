package dev.groknull.bpmner.agent

import com.embabel.agent.api.common.PromptRunner

data class BpmnRepairStrategyContext(
    val attempt: BpmnRepairAttempt,
    val promptRunner: PromptRunner,
)

interface BpmnRepairStrategy {
    fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult
}
