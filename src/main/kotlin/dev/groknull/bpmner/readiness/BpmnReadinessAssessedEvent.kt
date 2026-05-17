/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.jmolecules.event.annotation.DomainEvent

@DomainEvent
data class BpmnReadinessAssessedEvent(
    val request: BpmnRequest,
    val assessment: ProcessInputAssessment,
)
