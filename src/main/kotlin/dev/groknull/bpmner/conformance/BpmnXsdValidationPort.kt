/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import dev.groknull.bpmner.conformance.XsdValidationIssue
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
fun interface BpmnXsdValidationPort {
    fun validateDetailed(bpmnXml: String): List<XsdValidationIssue>
}
