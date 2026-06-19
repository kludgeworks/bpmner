/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

import dev.groknull.bpmner.domain.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
fun interface BpmnReadinessInvoker {
    fun assess(request: BpmnRequest): ProcessInputAssessment
}
