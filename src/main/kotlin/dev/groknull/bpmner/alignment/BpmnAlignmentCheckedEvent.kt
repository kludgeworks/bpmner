/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment

import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.core.BpmnRequest
import org.jmolecules.event.annotation.DomainEvent

@DomainEvent
data class BpmnAlignmentCheckedEvent(
    val request: BpmnRequest,
    val report: BpmnAlignmentReport,
)
