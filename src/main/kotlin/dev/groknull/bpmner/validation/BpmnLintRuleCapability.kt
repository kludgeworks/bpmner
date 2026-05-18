/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

data class BpmnLintRuleCapability(
    val id: String,
    val kind: RepairKind,
    val repairSafety: BpmnRepairSafety,
    val fixHandler: String?,
    val handlerExists: Boolean,
    val replacementMap: Map<String, String>?,
)
