package dev.groknull.bpmner.validation

import dev.groknull.bpmner.core.BpmnRepairSafety
import dev.groknull.bpmner.core.RepairKind

data class BpmnLintRuleCapability(
    val id: String,
    val kind: RepairKind,
    val repairSafety: BpmnRepairSafety,
    val fixHandler: String?,
    val handlerExists: Boolean,
    val replacementMap: Map<String, String>?,
    val layoutSensitive: Boolean = false,
)
