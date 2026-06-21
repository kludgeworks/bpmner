/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.bpmn.RepairKind
import dev.groknull.bpmner.bpmn.RepairSafety

data class BpmnLintRuleCapability(
    val id: String,
    val kind: RepairKind,
    val repairSafety: RepairSafety,
    val fixHandler: String?,
    val handlerExists: Boolean,
    val replacementMap: Map<String, String>?,
)
