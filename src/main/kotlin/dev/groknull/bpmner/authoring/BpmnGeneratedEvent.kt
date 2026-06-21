/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.RenderedBpmn
import org.jmolecules.event.annotation.DomainEvent

@DomainEvent
data class BpmnGeneratedEvent(
    val request: BpmnRequest,
    val rendered: RenderedBpmn,
)
