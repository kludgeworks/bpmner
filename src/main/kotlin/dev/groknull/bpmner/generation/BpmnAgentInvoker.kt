/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnAgentInvoker {
    fun generate(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): BpmnResult

    fun startAsync(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): String
}
