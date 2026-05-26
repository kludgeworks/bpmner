/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
fun interface BpmnRuleGuidancePort {
    fun getLlmRuleGuidance(): String
}
