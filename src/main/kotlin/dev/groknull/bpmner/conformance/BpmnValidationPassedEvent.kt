/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import dev.groknull.bpmner.bpmn.BpmnRequest
import org.jmolecules.event.annotation.DomainEvent

@DomainEvent
data class BpmnValidationPassedEvent(
    val request: BpmnRequest,
    val xml: String,
    val repairAttempts: Int,
    val processId: String? = null,
)
