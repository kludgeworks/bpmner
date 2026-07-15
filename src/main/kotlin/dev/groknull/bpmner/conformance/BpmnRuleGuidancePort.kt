/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import org.jmolecules.architecture.onion.simplified.ApplicationRing

@ApplicationRing
fun interface BpmnRuleGuidancePort {
    fun getLlmRuleGuidance(): String
}
