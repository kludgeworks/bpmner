/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.domain

import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.bpmn.BpmnRequest
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

    fun startAsync(
        request: BpmnRequest,
    ): String
}
