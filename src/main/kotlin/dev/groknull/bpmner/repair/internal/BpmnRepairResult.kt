package dev.groknull.bpmner.repair.internal

import com.embabel.chat.Message
import dev.groknull.bpmner.core.BpmnDefinition

sealed class BpmnRepairResult {
    data class Repaired(
        val definition: BpmnDefinition,
        val messages: List<Message>,
        val promptText: String,
    ) : BpmnRepairResult()

    data object NotApplicable : BpmnRepairResult()

    data class TerminalFailure(val reason: String) : BpmnRepairResult()
}
