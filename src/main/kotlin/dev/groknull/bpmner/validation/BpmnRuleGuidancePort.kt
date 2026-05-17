/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnRuleGuidancePort {
    fun getLlmRuleGuidance(): String
}
