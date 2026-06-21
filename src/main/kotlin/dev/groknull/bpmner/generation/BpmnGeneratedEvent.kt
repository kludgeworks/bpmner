/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.internal.model.RenderedBpmn
import org.jmolecules.event.annotation.DomainEvent

@DomainEvent
data class BpmnGeneratedEvent(
    val request: BpmnRequest,
    val rendered: RenderedBpmn,
)
