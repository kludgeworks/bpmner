/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.validation.BpmnAutoFixResult

data class AutoFixedBpmnXml(
    val definition: BpmnDefinition,
    val xml: String,
    val autoFixResult: BpmnAutoFixResult? = null,
)
