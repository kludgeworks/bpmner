/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.validation.XsdValidationIssue
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnXsdValidationPort {
    fun validateDetailed(bpmnXml: String): List<XsdValidationIssue>
}
