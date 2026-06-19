/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.domain.BpmnRequest
import dev.groknull.bpmner.validation.BpmnDiagnostic
import org.jmolecules.event.annotation.DomainEvent

@DomainEvent
data class BpmnValidationFailedEvent(
    val request: BpmnRequest,
    val xml: String,
    val diagnostics: List<BpmnDiagnostic>,
    val attemptNumber: Int,
    val repairAttempts: Int,
    val processId: String? = null,
)
